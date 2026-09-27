-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : BenchmarkTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Tests for benchmark.
module BenchmarkTests (tests) where

import Control.Monad (forM_)
import System.Directory (doesFileExist)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport

tests :: Env -> Test
tests env = TestLabel "Cabal runnable targets" $ TestCase $
  withFixtureNamed env "test/fixtures/run-benchmark" "project café" $ \project -> do
    let base = takeDirectory project
        output = base </> "output"
        common = ["--thc-root", thcRoot env, "--dist-dir", output]
        invoke backend target = run env project (Just backend) 240
          (["run", "--verify-artifacts"] ++ target ++ common ++ ["--runtime", runtime env])
        entry component plan = case filter
          ((== component) . string . (`field` "component-name")) (objects plan "install-plan") of
            [value] -> value
            _ -> error ("expected exactly one selected " ++ component)
        assertSelected component = do
          plan <- readJson (output </> "native/cache/plan.json")
          manifest <- readJson (output </> "packages.json")
          let selected = entry component plan
              identifier = string (field selected "id")
          assertBool ("manifest includes actual " ++ component)
            (any ((== identifier) . string . (`field` "id")) (objects manifest "units"))
          pure selected
    acquired <- run env project Nothing 240
      (["acquire", "run-benchmark:bench:measured"] ++ common)
    assertSuccess acquired
    assertNoStdout acquired
    audited <- doesFileExist (output </> "audit.json")
    assertBool "benchmark acquisition does not audit or execute" (not audited)
    selected <- assertSelected "bench:measured"
    native <- runExe env project Nothing 60 (string $ field selected "bin-file") []
    assertSuccess native
    assertNoStdout native
    forM_ [("bytecode", "bench:measured"), ("ast", "run-benchmark:bench:measured")] $ \(backend, target) -> do
      result <- invoke backend ([target] ++ [argument | backend == "ast",
        argument <- ["--project-file", "cabal.project"]])
      assertSuccess result
      assertEqual "benchmark output matches native" (out native) (out result)
      audit <- readJson (output </> "audit.json")
      assertBool "real benchmark Core accepted" (bool $ field audit "accepted")
      diagnostics <- json (last $ lines $ err result)
      assertEqual "selected backend" backend (string $ field diagnostics "backend")
      assertEqual "no unsupported traps" 0 (number $ field diagnostics "unsupportedTraps")
    -- No target, a package target and a bare component all use Cabal's resolver.
    forM_ [[], ["pkg:run-benchmark"], ["ordinary"], ["exe:ordinary"]] $ \target -> do
      result <- invoke "bytecode" target
      assertSuccess result
      assertNoStdout result
      _ <- assertSelected "exe:ordinary"
      pure ()
    disabledTest <- invoke "bytecode" ["run-benchmark:test:checked"]
    assertFailure disabledTest
    assertContains "explicitly disabled" (err disabledTest)
    checkedResult <- invoke "ast" ["run-benchmark:test:checked", "--enable-tests"]
    assertSuccess checkedResult
    assertNoStdout checkedResult
    testUnit <- assertSelected "test:checked"
    testManifest <- readJson (output </> "packages.json")
    let testModules = [string (field entryModule "name") |
          unit <- objects testManifest "units", field unit "id" == field testUnit "id",
          entryModule <- objects unit "modules"]
    assertBool "exitcode test other-modules included" ("Check" `elem` testModules)
    testNative <- runExe env project Nothing 60 (string $ field testUnit "bin-file") []
    assertSuccess testNative
    assertEqual "exitcode test output matches native" (out testNative) (out checkedResult)
    -- Like cabal run, a bare name also naming the package's main library
    -- selects that library, not its otherwise unique executable.
    forM_ ["bench:missing", "other:bench:measured", "bench:disabled",
           "lib:run-benchmark", "run-benchmark"] $ \target -> do
      rejected <- invoke "bytecode" [target]
      assertFailure rejected
      assertNoStdout rejected
    detailed <- invoke "bytecode" ["test:detailed", "--enable-tests"]
    assertFailure detailed
    assertNoStdout detailed
    assertContains "only exitcode-stdio-1.0 test suites are supported" (err detailed)
    -- Cabal's default falls back to the sole runnable when no executable is enabled.
    let declaration = project </> "run-benchmark.cabal"
    original <- readText declaration
    writeText declaration (replaceText "executable ordinary\n" "executable ordinary\n  buildable: False\n" original)
    fallback <- invoke "bytecode" []
    assertSuccess fallback
    _ <- assertSelected "bench:measured"
    current <- readText declaration
    writeText declaration (current ++ unlines
      ["", "benchmark other", "  type: exitcode-stdio-1.0", "  main-is: Main.hs",
       "  other-modules: Check",
       "  hs-source-dirs: bench", "  build-depends: base, run-benchmark", "  default-language: Haskell2010"])
    ambiguous <- invoke "bytecode" []
    assertFailure ambiguous
    assertNoStdout ambiguous
    assertContains "Cabal runnable target selection failed" (err ambiguous)
