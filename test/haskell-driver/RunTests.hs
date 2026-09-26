-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module RunTests (tests) where

import Control.Monad (forM_)
import System.Directory (canonicalizePath, doesDirectoryExist)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "single-package native versus THC run" $ TestCase $
  withFixture env "test/fixtures/run-pure" $ \package -> do
    let base = takeDirectory package
        source = package </> "app/Main.hs"
        -- Native compilation is independent of the THC backend. Keep the first
        -- build cold, then let Cabal reuse unchanged objects while every run
        -- still re-exports/audits Core and executes both THC and the native oracle.
        -- Later edits use this same directory, exercising actual invalidation.
        output = base </> "output"
        invokeFfi backend ffi = run env base backend 180
          (["run", package </> "run-pure.cabal", "--exe", "completed", "--dist-dir", output,
            "--thc-root", thcRoot env, "--runtime", runtime env] ++ ffi)
        invoke backend = invokeFfi backend ["--ffi", "native"]
        exported = output </> "thc-run/completed"
        native = output </> "build/completed/completed"
    cold <- doesDirectoryExist output
    assertBool "fixture starts with a cold native dist directory" (not cold)
    forM_ ["bytecode", "ast"] $ \backend -> do
      result <- invoke (Just backend)
      assertSuccess result
      assertNoStdout result
      diagnostics <- json (last $ lines $ err result)
      assertEqual "backend" backend (string $ field diagnostics "backend")
      assertEqual "unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
      audit <- readJson (exported </> "audit.json")
      assertBool "strict Core accepted" (bool $ field audit "accepted")
      assertEqual "IO root" ["main:Main.main"] (strings $ field audit "roots")
      let primitives = map (string . (`field` "name")) (objects audit "primitives")
      forM_ ["newMutVar#", "writeMutVar#", "readMutVar#", "raise#"] $ \primitive ->
        assertBool ("missing " ++ primitive) (primitive `elem` primitives)
      forM_ ["Main", "Answer"] $ \moduleName -> do
        core <- readJson (exported </> "core" </> moduleName ++ ".json")
        assertEqual "source notes" "source-notes-metadata" (string $ field (field core "lowering") "ticks")
        assertBool "source spans" (not $ null $ objects core "sourceSpans")
        sourcePath <- canonicalizePath (package </> "app" </> moduleName ++ ".hs")
        hasSource <- anyM (\file -> do
          path <- canonicalizePath (string $ field file "path")
          pure (path == sourcePath && string (field file "content") /= ""))
          (objects core "sourceFiles")
        assertBool "source content" hasSource
        requireFile (exported </> "ghc" </> moduleName ++ ".hi")
      forM_ [".o", ".dyn_o"] $ \suffix -> do
        objectsFound <- findFiles (exported </> "ghc") suffix
        assertBool "no duplicate native objects" (null objectsFound)
      requireFile native
      nativeResult <- runExe env package Nothing 60 native []
      assertSuccess nativeResult
      assertEqual "native stdout" (out nativeResult) (out result)

    managed <- invokeFfi Nothing ["--ffi", "managed"]
    assertFailure managed
    assertNoStdout managed
    assertContains "--ffi managed is unavailable" (err managed)

    original <- readText source
    assertContains "answer ==# 42#" original
    writeText source (replaceText "answer ==# 42#" "answer ==# 43#" original)
    forM_ ["bytecode", "ast"] $ \backend -> do
      result <- invoke (Just backend)
      assertFailure result
      audit <- readJson (exported </> "audit.json")
      assertBool "valid Core still accepted" (bool $ field audit "accepted")
      requireFile native
      nativeResult <- runExe env package Nothing 60 native []
      assertFailure nativeResult

    writeText source "module Main where\nmain :: IO ()\nmain = putStrLn \"native only\"\n"
    unsupported <- invoke Nothing
    assertFailure unsupported
    assertNoStdout unsupported
    audit <- readJson (exported </> "audit.json")
    assertBool "console IO rejected" (not $ bool $ field audit "accepted")
    requireFile native
    nativeResult <- runExe env package Nothing 60 native []
    assertSuccess nativeResult
    assertEqual "native console output" "native only\n" (out nativeResult)

    writeText source "module Main where\nmain :: IO Int\nmain = pure 42\n"
    wrongResult <- invoke Nothing
    assertFailure wrongResult
    assertNoStdout wrongResult
    wrongAudit <- readJson (exported </> "audit.json")
    assertBool "IO Int rejected" (not $ bool $ field wrongAudit "accepted")
    assertBool "correct boundary code" $ any
      ((== "io-main-boundary") . string . (`field` "code")) (objects wrongAudit "issues")

anyM :: Monad m => (a -> m Bool) -> [a] -> m Bool
anyM predicate values = or <$> mapM predicate values
