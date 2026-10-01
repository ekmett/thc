-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : RunOptionsTests
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and driver test dependencies
--
-- Tests for run options.
module RunOptionsTests (tests) where

import Control.Monad (forM_)
import Data.List (isInfixOf)
import System.Directory (canonicalizePath, createFileLink)
import System.Environment (getExecutablePath)
import System.FilePath ((</>))
import System.Info (os)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport
import NativeCacheTests (withEnvironment, withScratch)
import THC.Driver.Run (runtimeLaunchArguments, runtimeEntryArguments, runtimeNativeEnvironment)

tests :: Env -> Test
tests env = TestLabel "run options and target selection" $ TestList
  [ TestLabel "launcher receives the canonical current producer and disposable cache, not inherited replacements" $ TestCase $
      withScratch $ \directory -> withEnvironment [("THC_CACHE_HOME", directory)] $ do
        driverPath <- canonicalizePath =<< getExecutablePath
        let inherited = [("THC_PACKAGE_NATIVE_BUILDER", "untrusted-driver"),
              ("JAVA_OPTS", "-Dunchanged=true"), ("THC_PACKAGE_NATIVE_CACHE", "untrusted-cache"),
              ("THC_PACKAGE_NATIVE_BUILDER", "second-driver"), ("OTHER", "opaque value")]
        assertEqual "exact trusted pair and unrelated host settings"
          [("THC_PACKAGE_NATIVE_BUILDER", driverPath), ("THC_PACKAGE_NATIVE_CACHE", directory </> "native-adapters"),
           ("JAVA_OPTS", "-Dunchanged=true"), ("OTHER", "opaque value")]
          =<< runtimeNativeEnvironment inherited
  , TestLabel "find THC beside the executable from an unrelated project" $ TestCase $
      if os == "mingw32" then pure () else
      withFixture env "t/fixtures/run-pure" $ \package -> do
        let missingRuntime = package </> "missing-runtime"
            arguments = ["run", "completed", "--runtime", missingRuntime]
        direct <- run env package Nothing 30 arguments
        assertFailure direct
        assertContains missingRuntime (unwords (words (err direct)))
        let link = package </> "linked-thc"
        createFileLink (driver env) link
        linked <- runExe env package Nothing 30 link arguments
        assertFailure linked
        assertContains missingRuntime (unwords (words (err linked)))
  , TestLabel "launcher prefix and guest suffix" $ TestList
      [ TestLabel (show verify ++ " " ++ show entry ++ " " ++ show guest) $ TestCase $
          assertEqual "exact separate arguments"
            (["--verify-artifacts" | verify] ++ entry ++ ["--", "program"] ++ guest)
            (runtimeLaunchArguments verify entry "program" guest)
      | verify <- [False, True]
      , entry <- [["--run-io", "core one.json,core-two.json", "main:Main.main"],
                  ["--run-io", "@packages.json", "selected:Main.main"],
                  ["--run-executable", "@packages.json", "main::Main.main", "flushStdHandles"]]
      , guest <- [[], ["--guest-option", "value", "--verify-artifacts", "--", "", "two words", "lambda-λ"]]
      ]
  , TestLabel "loose consumers keep paths before the guest boundary" $ TestCase $ do
      let modules = ["C:/core café/Main.json", "C:/core café/THC.InterfaceClosure.json"]
          entry = runtimeEntryArguments modules "C:/support/packages.json" "main:Main.main"
      assertEqual "exact module association, manifest and guest operands"
        ["--verify-artifacts",
         "--run-io", "C:/core café/Main.json,C:/core café/THC.InterfaceClosure.json,@C:/support/packages.json", "main:Main.main",
         "--", "program", "--json-sidecar", "guest", "", "--"]
        (runtimeLaunchArguments True entry "program" ["--json-sidecar", "guest", "", "--"])
  , TestLabel "verification is an explicit run-only switch" $ TestCase $ do
      accepted <- parseOnly ["run", "--verify-artifacts"]
      assertFailure accepted
      assertContains "THC root directory does not exist" (err accepted)
      forM_ [["acquire", "--verify-artifacts"], ["run", "--verify-artifacts=true"]] $ \arguments -> do
        rejected <- parseOnly arguments
        assertFailure rejected
        assertNoStdout rejected
        assertBool "verification option rejected" (not ("THC root directory does not exist" `isInfixOf` err rejected))
      guest <- parseOnly ["run", "--", "--verify-artifacts=true"]
      assertFailure guest
      assertContains "THC root directory does not exist" (err guest)
  , TestLabel "ordinary project runs do not require the auditor" $ TestCase $
      -- Windows uses the simple-package backend, which configures Cabal before
      -- checking tools. This prerequisite-only control must not start a build.
      if os == "mingw32" then pure () else
      withFixtureNamed env "t/fixtures/run-pure" "missing auditor" $ \package -> do
        let launcher = package </> "launcher"
            arguments = ["run", "--thc-root", package, "--runtime", launcher]
        writeText launcher "not executed\n"
        ordinary <- run env package Nothing 30 arguments
        assertFailure ordinary
        assertContains "bin/build-compiler.sh" (err ordinary)
        assertBool "default skips the missing auditor" (not ("audit-core.py" `isInfixOf` err ordinary))
        verified <- run env package Nothing 30 (arguments ++ ["--verify-artifacts"])
        assertFailure verified
        assertContains "bin/audit-core.py" (err verified)
  , TestLabel "CLI rejects unknown options before building" $ TestCase $
      forM_ [["--unknown-option", "value"], ["--unknown-option=value"]] $ \arguments -> do
        result <- parseOnly ("run" : arguments)
        assertFailure result
        assertNoStdout result
        assertContains "unrecognized option" (err result)
  , TestLabel "runtime requires a value" $ TestCase $ do
      result <- parseOnly ["run", "--runtime"]
      assertFailure result
      assertNoStdout result
      assertContains "requires an argument" (err result)
  , TestLabel "help names artifact verification" $ TestCase $ do
      result <- parseOnly ["run", "--help"]
      assertSuccess result
      assertContains "--verify-artifacts" (out result)
      assertContains "verify runtime artifacts (default: off)" (out result)
  , TestLabel "positional Cabal targets and omitted default" $ TestCase $
      forM_ ["run", "acquire"] $ \command ->
      forM_ [[], ["ordinary"], ["exe:ordinary"], ["example:exe:ordinary"],
             ["bench:measured"], ["example:bench:measured"], ["example:test:checked"]] $ \target -> do
        result <- parseOnly (command : target)
        assertFailure result
        assertContains "THC root directory does not exist" (err result)
  , TestLabel "legacy selector flags are not aliases" $ TestCase $
      forM_ ["run", "acquire"] $ \command ->
      forM_ ["--exe", "--target", "--bench"] $ \flag -> do
        result <- parseOnly [command, flag, "ordinary"]
        assertFailure result
        assertNoStdout result
        assertContains "unrecognized option" (err result)
  , TestLabel "project location flags follow Cabal" $ TestCase $
      forM_ [["--project-dir", "."], ["--project-file", "cabal.project"]] $ \location -> do
        result <- parseOnly ("run" : "bench:measured" : location)
        assertFailure result
        assertContains "THC root directory does not exist" (err result)
  , TestLabel "multiple runnable targets are rejected" $ TestCase $ do
      result <- parseOnly ["run", "ordinary", "bench:measured"]
      assertFailure result
      assertContains "Usage: thc run [TARGET]" (err result)
  , TestLabel "target-like guest arguments are not parsed as driver selectors" $ TestCase $ do
      result <- parseOnly
        ["run", "bench:measured", "--", "--exe", "guest-option", "", "--"]
      assertFailure result
      assertContains "THC root directory does not exist" (err result)
  , TestLabel "driver help follows ordinary option ordering" $ TestCase $
      forM_ ["run", "acquire"] $ \command ->
      forM_ ["--help", "-h"] $ \help ->
      forM_ [[help], ["example:bench:measured", help],
             [help, "example:test:checked"],
             ["example:exe:ordinary", "--project-dir", "missing-project", help]] $ \arguments -> do
        result <- parseOnly (command : arguments)
        assertSuccess result
        assertEqual "help needs no project/build and writes no diagnostic" "" (err result)
        assertContains ("Usage: thc " ++ command ++ " [TARGET]") (out result)
        assertContains "--help" (out result)
        assertContains "Show this help text" (out result)
  , TestLabel "guest help does not request driver help" $ TestCase $
      forM_ ["--help", "-h"] $ \help -> do
        result <- parseOnly ["run", "example:bench:measured", "--", help]
        assertFailure result
        assertNoStdout result
        assertContains "THC root directory does not exist" (err result)
  , TestLabel "help spelling as a required option value is opaque" $ TestCase $
      forM_ ["run", "acquire"] $ \command -> do
        result <- parseOnly [command, "example:bench:measured", "--project-dir", "--help"]
        assertFailure result
        assertNoStdout result
        assertContains "THC root directory does not exist" (err result)
  ]
  where
    -- Stop accepted parses at root validation, before configuring any project.
    parseOnly arguments = run env (root env) Nothing 30 $ case arguments of
      command : rest -> command : "--thc-root" : (scratch env </> "nonexistent-root") : rest
      [] -> []
