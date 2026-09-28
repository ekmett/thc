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
import System.FilePath ((</>))
import System.Info (os)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport
import THC.Driver.Run (FfiMode(..), parseFfiMode, runtimeLaunchArguments, runtimeEntryArguments)

tests :: Env -> Test
tests env = TestLabel "run options and target selection" $ TestList
  [ TestLabel "typed mode choices" $ TestCase $ do
      assertEqual "native" (Right NativeFfi) (parseFfiMode "native")
      assertEqual "managed" (Right ManagedFfi) (parseFfiMode "managed")
      forM_ ["", "MANAGED", "automatic", "native,managed"] $ \invalid ->
        case parseFfiMode invalid of
          Left problem -> assertContains "--ffi must be native or managed" problem
          Right mode -> assertBool ("unexpected accepted mode " ++ show mode) False
  , TestLabel "launcher prefix and guest suffix" $ TestList
      [ TestLabel (show verify ++ " " ++ show mode ++ " " ++ show entry ++ " " ++ show guest) $ TestCase $
          assertEqual "exact separate arguments"
            (["--verify-artifacts" | verify] ++ prefix ++ entry ++ ["--", "program"] ++ guest)
            (runtimeLaunchArguments verify mode entry "program" guest)
      | (mode, prefix) <- [(Nothing, []), (Just NativeFfi, ["--ffi", "native"]),
                          (Just ManagedFfi, ["--ffi", "managed"])]
      , verify <- [False, True]
      , entry <- [["--run-io", "core one.json,core-two.json", "main:Main.main"],
                  ["--run-io", "@packages.json", "selected:Main.main"],
                  ["--run-executable", "@packages.json", "main::Main.main", "flushStdHandles"]]
      , guest <- [[], ["--ffi", "not-a-runtime-mode", "--verify-artifacts", "--", "", "two words", "lambda-λ"]]
      ]
  , TestLabel "loose consumers keep paths before the guest boundary" $ TestCase $ do
      let modules = ["C:/core café/Main.json", "C:/core café/THC.InterfaceClosure.json"]
          entry = runtimeEntryArguments modules "C:/support/packages.json" "main:Main.main"
      assertEqual "exact module association, manifest and guest operands"
        ["--verify-artifacts", "--ffi", "native",
         "--run-io", "C:/core café/Main.json,C:/core café/THC.InterfaceClosure.json,@C:/support/packages.json", "main:Main.main",
         "--", "program", "--json-sidecar", "guest", "", "--"]
        (runtimeLaunchArguments True (Just NativeFfi) entry "program" ["--json-sidecar", "guest", "", "--"])
  , TestLabel "verification is an explicit run-only switch" $ TestCase $ do
      accepted <- run env (root env) Nothing 30 ["run", "--verify-artifacts"]
      assertFailure accepted
      assertContains "run requires --thc-root DIR" (err accepted)
      forM_ [["acquire", "--verify-artifacts"], ["run", "--verify-artifacts=true"]] $ \arguments -> do
        rejected <- run env (root env) Nothing 30 arguments
        assertFailure rejected
        assertNoStdout rejected
        assertBool "verification option rejected" (not ("requires --thc-root DIR" `isInfixOf` err rejected))
      guest <- run env (root env) Nothing 30 ["run", "--", "--verify-artifacts=true"]
      assertFailure guest
      assertContains "run requires --thc-root DIR" (err guest)
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
  , TestLabel "CLI rejects invalid selection before building" $ TestCase $
      forM_ ["", "MANAGED", "automatic", "native,managed"] $ \invalid -> do
        result <- run env (root env) Nothing 30 ["run", "--ffi", invalid]
        assertFailure result
        assertNoStdout result
        assertContains "--ffi must be native or managed" (err result)
  , TestLabel "CLI accepts both choices and equals syntax" $ TestCase $
      forM_ [["--ffi", "native"], ["--ffi", "managed"],
             ["--ffi=native"], ["--ffi=managed"]] $ \arguments -> do
        -- Missing --thc-root is intentionally checked before any build or runtime
        -- probe; reaching it verifies that the option was accepted by the CLI.
        result <- run env (root env) Nothing 30 ("run" : arguments)
        assertFailure result
        assertContains "run requires --thc-root DIR" (err result)
        assertBool "not an option-parser rejection" (not ("unrecognized option" `isInfixOf` err result))
  , TestLabel "CLI does not interpret a guest same-spelled option" $ TestCase $
      forM_ [[], ["--ffi", "managed"]] $ \arguments -> do
        result <- run env (root env) Nothing 30
          ("run" : arguments ++ ["--", "--ffi", "not-a-runtime-mode", "", "--"])
        assertFailure result
        assertContains "run requires --thc-root DIR" (err result)
        assertBool "guest mode value was not validated"
          (not ("--ffi must be native or managed" `isInfixOf` err result))
  , TestLabel "unreleased old spelling is not an alias" $ TestCase $
      forM_ [["--sulong-mode", "native"], ["--sulong-mode=managed"]] $ \arguments -> do
        result <- run env (root env) Nothing 30 ("run" : arguments)
        assertFailure result
        assertNoStdout result
        assertContains "unrecognized option" (err result)
  , TestLabel "mode requires a value" $ TestCase $ do
      result <- run env (root env) Nothing 30 ["run", "--ffi"]
      assertFailure result
      assertNoStdout result
      assertContains "requires an argument" (err result)
  , TestLabel "help names the modes" $ TestCase $ do
      result <- run env (root env) Nothing 30 ["run", "--help"]
      assertSuccess result
      assertContains "--ffi" (out result)
      assertContains "native|managed" (out result)
      assertContains "unavailable managed execution fails explicitly" (out result)
      assertContains "--verify-artifacts" (out result)
      assertContains "verify runtime artifacts (default: off)" (out result)
      assertBool "old spelling is absent from help" (not ("--sulong-mode" `isInfixOf` out result))
  , TestLabel "positional Cabal targets and omitted default" $ TestCase $
      forM_ ["run", "acquire"] $ \command ->
      forM_ [[], ["ordinary"], ["exe:ordinary"], ["example:exe:ordinary"],
             ["bench:measured"], ["example:bench:measured"], ["example:test:checked"]] $ \target -> do
        result <- run env (root env) Nothing 30 (command : target)
        assertFailure result
        assertContains (command ++ " requires --thc-root DIR") (err result)
  , TestLabel "legacy selector flags are not aliases" $ TestCase $
      forM_ ["run", "acquire"] $ \command ->
      forM_ ["--exe", "--target", "--bench"] $ \flag -> do
        result <- run env (root env) Nothing 30 [command, flag, "ordinary"]
        assertFailure result
        assertNoStdout result
        assertContains "unrecognized option" (err result)
  , TestLabel "project location flags follow Cabal" $ TestCase $
      forM_ [["--project-dir", "."], ["--project-file", "cabal.project"]] $ \location -> do
        result <- run env (root env) Nothing 30 ("run" : "bench:measured" : location)
        assertFailure result
        assertContains "run requires --thc-root DIR" (err result)
  , TestLabel "multiple runnable targets are rejected" $ TestCase $ do
      result <- run env (root env) Nothing 30 ["run", "ordinary", "bench:measured"]
      assertFailure result
      assertContains "Usage: thc run [TARGET]" (err result)
  , TestLabel "target-like guest arguments are not parsed as driver selectors" $ TestCase $ do
      result <- run env (root env) Nothing 30
        ["run", "bench:measured", "--", "--exe", "guest-option", "", "--"]
      assertFailure result
      assertContains "run requires --thc-root DIR" (err result)
  , TestLabel "driver help follows ordinary option ordering" $ TestCase $
      forM_ ["run", "acquire"] $ \command ->
      forM_ ["--help", "-h"] $ \help ->
      forM_ [[help], ["example:bench:measured", help],
             [help, "example:test:checked"],
             ["example:exe:ordinary", "--project-dir", "missing-project", help]] $ \arguments -> do
        result <- run env (root env) Nothing 30 (command : arguments)
        assertSuccess result
        assertEqual "help needs no project/build and writes no diagnostic" "" (err result)
        assertContains ("Usage: thc " ++ command ++ " [TARGET]") (out result)
        assertContains "--help" (out result)
        assertContains "Show this help text" (out result)
  , TestLabel "guest help does not request driver help" $ TestCase $
      forM_ ["--help", "-h"] $ \help -> do
        result <- run env (root env) Nothing 30 ["run", "example:bench:measured", "--", help]
        assertFailure result
        assertNoStdout result
        assertContains "run requires --thc-root DIR" (err result)
  , TestLabel "help spelling as a required option value is opaque" $ TestCase $
      forM_ ["run", "acquire"] $ \command -> do
        result <- run env (root env) Nothing 30 [command, "example:bench:measured", "--project-dir", "--help"]
        assertFailure result
        assertNoStdout result
        assertContains (command ++ " requires --thc-root DIR") (err result)
  ]
