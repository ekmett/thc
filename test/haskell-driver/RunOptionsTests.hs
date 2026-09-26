-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module RunOptionsTests (tests) where

import Control.Monad (forM_)
import Data.List (isInfixOf)
import Test.HUnit (Test(..), assertBool, assertEqual)
import TestSupport
import THC.Driver.Run (SulongMode(..), parseSulongMode, runtimeLaunchArguments)

tests :: Env -> Test
tests env = TestLabel "runtime-only Sulong selection" $ TestList
  [ TestLabel "typed mode choices" $ TestCase $ do
      assertEqual "native" (Right NativeSulong) (parseSulongMode "native")
      assertEqual "managed" (Right ManagedSulong) (parseSulongMode "managed")
      forM_ ["", "MANAGED", "automatic", "native,managed"] $ \invalid ->
        case parseSulongMode invalid of
          Left problem -> assertContains "--sulong-mode must be native or managed" problem
          Right mode -> assertBool ("unexpected accepted mode " ++ show mode) False
  , TestLabel "launcher prefix and guest suffix" $ TestList
      [ TestLabel (show mode ++ " " ++ show entry ++ " " ++ show guest) $ TestCase $
          assertEqual "exact separate arguments"
            (prefix ++ entry ++ ["--", "program"] ++ guest)
            (runtimeLaunchArguments mode entry "program" guest)
      | (mode, prefix) <- [(Nothing, []), (Just NativeSulong, ["--sulong-mode", "native"]),
                          (Just ManagedSulong, ["--sulong-mode", "managed"])]
      , entry <- [["--run-io", "core one.json,core-two.json", "main:Main.main"],
                  ["--run-io", "@packages.json", "selected:Main.main"],
                  ["--run-executable", "@packages.json", "main::Main.main", "flushStdHandles"]]
      , guest <- [[], ["--sulong-mode", "not-a-runtime-mode", "--", "", "two words", "lambda-λ"]]
      ]
  , TestLabel "CLI rejects invalid selection before building" $ TestCase $
      forM_ ["", "MANAGED", "automatic", "native,managed"] $ \invalid -> do
        result <- run env (root env) Nothing 30 ["run", "--sulong-mode", invalid]
        assertFailure result
        assertNoStdout result
        assertContains "--sulong-mode must be native or managed" (err result)
  , TestLabel "CLI accepts both choices and equals syntax" $ TestCase $
      forM_ [["--sulong-mode", "native"], ["--sulong-mode", "managed"],
             ["--sulong-mode=native"], ["--sulong-mode=managed"]] $ \arguments -> do
        -- Missing --exe is intentionally checked before any build or runtime
        -- probe; reaching it verifies that the option was accepted by the CLI.
        result <- run env (root env) Nothing 30 ("run" : arguments)
        assertFailure result
        assertContains "run requires --exe NAME" (err result)
        assertBool "not an option-parser rejection" (not ("unrecognized option" `isInfixOf` err result))
  , TestLabel "CLI does not interpret a guest same-spelled option" $ TestCase $
      forM_ [[], ["--sulong-mode", "managed"]] $ \arguments -> do
        result <- run env (root env) Nothing 30
          ("run" : arguments ++ ["--", "--sulong-mode", "not-a-runtime-mode", "", "--"])
        assertFailure result
        assertContains "run requires --exe NAME" (err result)
        assertBool "guest mode value was not validated"
          (not ("--sulong-mode must be native or managed" `isInfixOf` err result))
  , TestLabel "help names the modes" $ TestCase $ do
      result <- run env (root env) Nothing 30 ["run", "--help"]
      assertSuccess result
      assertContains "--sulong-mode" (out result)
      assertContains "native|managed" (out result)
  ]
