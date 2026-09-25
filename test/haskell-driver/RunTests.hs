-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module RunTests (tests) where

import Control.Monad (forM_)
import System.Directory (canonicalizePath)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "single-package native versus THC run" $ TestCase $
  withFixture env "test/fixtures/run-pure" $ \package -> do
    let base = takeDirectory package
        source = package </> "app/Main.hs"
        invoke label backend = do
          let output = base </> label
          result <- run env base backend 180
            ["run", package </> "run-pure.cabal", "--exe", "completed", "--dist-dir", output,
             "--thc-root", thcRoot env, "--runtime", runtime env]
          pure (result, output)
        exported output = output </> "thc-run/completed"
        native output = output </> "build/completed/completed"
    forM_ ["bytecode", "ast"] $ \backend -> do
      (result, output) <- invoke ("success-" ++ backend) (Just backend)
      assertSuccess result
      assertNoStdout result
      diagnostics <- json (last $ lines $ err result)
      assertEqual "backend" backend (string $ field diagnostics "backend")
      assertEqual "unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
      audit <- readJson (exported output </> "audit.json")
      assertBool "strict Core accepted" (bool $ field audit "accepted")
      assertEqual "GHC executable IO roots" ["main::Main.main",
        "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"] (strings $ field audit "roots")
      mainCore <- readJson (exported output </> "core/Main.json")
      assertBool "GHC-generated wrapper exported" $ any
        ((== "main::Main.main") . string . (`field` "id"))
        (objects mainCore "bindings")
      let primitives = map (string . (`field` "name")) (objects audit "primitives")
      forM_ ["newMutVar#", "writeMutVar#", "readMutVar#", "raise#"] $ \primitive ->
        assertBool ("missing " ++ primitive) (primitive `elem` primitives)
      forM_ ["Main", "Answer"] $ \moduleName -> do
        core <- readJson (exported output </> "core" </> moduleName ++ ".json")
        assertEqual "source notes" "source-notes-metadata" (string $ field (field core "lowering") "ticks")
        assertBool "source spans" (not $ null $ objects core "sourceSpans")
        sourcePath <- canonicalizePath (package </> "app" </> moduleName ++ ".hs")
        hasSource <- anyM (\file -> do
          path <- canonicalizePath (string $ field file "path")
          pure (path == sourcePath && string (field file "content") /= ""))
          (objects core "sourceFiles")
        assertBool "source content" hasSource
        requireFile (exported output </> "ghc" </> moduleName ++ ".hi")
      forM_ [".o", ".dyn_o"] $ \suffix -> do
        objectsFound <- findFiles (exported output </> "ghc") suffix
        assertBool "no duplicate native objects" (null objectsFound)
      requireFile (native output)
      nativeResult <- runExe env package Nothing 60 (native output) []
      assertSuccess nativeResult
      assertEqual "native stdout" (out nativeResult) (out result)

    original <- readText source
    assertContains "answer ==# 42#" original
    writeText source (replaceText "answer ==# 42#" "answer ==# 43#" original)
    forM_ ["bytecode", "ast"] $ \backend -> do
      (result, output) <- invoke ("guest-failure-" ++ backend) (Just backend)
      assertFailure result
      audit <- readJson (exported output </> "audit.json")
      assertBool "valid Core still accepted" (bool $ field audit "accepted")
      requireFile (native output)
      nativeResult <- runExe env package Nothing 60 (native output) []
      assertFailure nativeResult

    writeText source "module Main where\nmain :: IO ()\nmain = putStrLn \"native only\"\n"
    (unsupported, output) <- invoke "unsupported-io" Nothing
    assertFailure unsupported
    assertNoStdout unsupported
    audit <- readJson (exported output </> "audit.json")
    assertBool "console IO rejected" (not $ bool $ field audit "accepted")
    requireFile (native output)
    nativeResult <- runExe env package Nothing 60 (native output) []
    assertSuccess nativeResult
    assertEqual "native console output" "native only\n" (out nativeResult)

    writeText source "module Main where\nmain :: IO Int\nmain = pure 42\n"
    (wrongResult, output2) <- invoke "wrong-io-result" Nothing
    assertFailure wrongResult
    assertNoStdout wrongResult
    wrongAudit <- readJson (exported output2 </> "audit.json")
    assertBool "IO Int rejected" (not $ bool $ field wrongAudit "accepted")
    assertBool "correct boundary code" $ any
      ((== "io-main-boundary") . string . (`field` "code")) (objects wrongAudit "issues")

anyM :: Monad m => (a -> m Bool) -> [a] -> m Bool
anyM predicate values = or <$> mapM predicate values
