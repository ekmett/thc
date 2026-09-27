-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : RunTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Tests for run.
module RunTests (tests) where

import Control.Monad (forM_)
import System.Directory (canonicalizePath, doesDirectoryExist)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "implicit Cabal project native versus THC run" $ TestCase $
  -- GHC's Linux -g assembler cannot quote a double quote in .file paths.
  -- Plan tests retain quoted paths; this project capture retains spaces/Unicode.
  withFixtureNamed env "test/fixtures/run-pure" "project café" $ \package -> do
    let base = takeDirectory package
        source = package </> "app/Main.hs"
        -- Native compilation is independent of the THC backend. Keep the first
        -- build cold, then let Cabal reuse unchanged objects while every run
        -- still re-exports/audits Core and executes both THC and the native oracle.
        -- Later edits use this same directory, exercising actual invalidation.
        output = base </> "output"
        invokeFfi backend ffi = run env base backend 180
          (["run", "--project-dir", package, "completed", "--dist-dir", output,
            "--thc-root", thcRoot env, "--runtime", runtime env] ++ ffi)
        invoke backend = invokeFfi backend ["--ffi", "native"]
        exported = output
        executable = do
          plan <- readJson (output </> "native/cache/plan.json")
          pure $ string $ field (one ((== "exe:completed") . string . (`field` "component-name"))
            (objects plan "install-plan")) "bin-file"
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
      manifest <- readJson (output </> "packages.json")
      let entry = one (any ((== "Main") . string . (`field` "name")) . (`objects` "modules"))
                      (objects manifest "units")
          bundle = string $ field (field entry "bundle") "path"
      assertEqual "IO root" [string (field entry "id") ++ ":Main.main"] (strings $ field audit "roots")
      let primitives = map (string . (`field` "name")) (objects audit "primitives")
      forM_ ["newMutVar#", "writeMutVar#", "readMutVar#", "raise#"] $ \primitive ->
        assertBool ("missing " ++ primitive) (primitive `elem` primitives)
      forM_ ["Main", "Answer"] $ \moduleName -> do
        let moduleEntry = one ((== moduleName) . string . (`field` "name")) (objects entry "modules")
        core <- readCore bundle (string $ field moduleEntry "path")
        -- Project bundles use the post-Tidy schema, whose actual source tables
        -- replace the older simplifier schema's lowering/ticks marker.
        assertEqual "post-Tidy Core" "optimized-Core-after-Tidy-before-CorePrep"
          (string $ field core "boundary")
        assertBool "source spans" (not $ null $ objects core "sourceSpans")
        sourcePath <- canonicalizePath (package </> "app" </> moduleName ++ ".hs")
        hasSource <- anyM (\file -> do
          path <- canonicalizePath (string $ field file "path")
          pure (path == sourcePath && string (field file "content") /= ""))
          (objects core "sourceFiles")
        assertBool "source content" hasSource
        native <- executable
        requireFile (takeDirectory native </> "completed-tmp" </> moduleName ++ ".hi")
      -- Temporary export objects never become another native component build.
      forM_ [".o", ".dyn_o"] $ \suffix -> do
        objectsFound <- findFiles (output </> "native/cache/thc/staging") suffix
        assertBool "temporary export objects cleaned" (null objectsFound)
      native <- executable
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
      native <- executable
      requireFile native
      nativeResult <- runExe env package Nothing 60 native []
      assertFailure nativeResult

    writeText source "module Main where\nmain :: IO ()\nmain = putStrLn \"native only\"\n"
    unsupported <- invoke Nothing
    assertFailure unsupported
    assertNoStdout unsupported
    audit <- readJson (exported </> "audit.json")
    assertBool "console IO rejected" (not $ bool $ field audit "accepted")
    native <- executable
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

one :: (a -> Bool) -> [a] -> a
one predicate values = case filter predicate values of
  [value] -> value
  _ -> error "expected exactly one selected component or module"
