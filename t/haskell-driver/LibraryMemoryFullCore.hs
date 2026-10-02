-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; HUnit and driver test dependencies
--
-- Tests for library memory full core.
module Main (main) where

import Control.Monad (forM_)
import System.Environment (lookupEnv)
import System.Exit (exitFailure)
import System.FilePath ((</>))
import Test.HUnit (Counts (..), Test (..), assertBool, assertEqual, runTestTT)
import TestSupport

main :: IO ()
main = do
  environment <- setup
  counts <- runTestTT (fixture environment)
  if errors counts + failures counts == 0 then pure () else exitFailure

fixture :: Env -> Test
fixture environment = TestLabel "public package arithmetic, text and memory" $ TestCase $
  withFixtureNamed environment "t/fixtures/run-library-memory" "library memory" $ \project -> do
    installedCore <- maybe "required" id <$> lookupEnv "THC_TEST_INSTALLED_CORE"
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = scratch environment </> "library-memory-full-core"
        command = ["run", "--verify-artifacts", "--project-dir", project, "library-memory", "--installed-core", installedCore,
          "--thc-root", thcRoot environment, "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\path -> ["--with-ghc", path]) installedGhc ++
          maybe [] (\path -> ["--with-ghc-pkg", path]) installedPkg ++
          maybe [] (\path -> ["--ghc-source", path]) ghcSource
        expected = unlines
          [ "([2,3,5,7],[2,88,5,7])"
          , "[65,66]"
          -- 2^64 + 64 carries into a second limb. Truncating the negative
          -- square plus 65 gives quotient -(2^64 + 64) and remainder -65.
          , "(18446744073709551680,(-18446744073709551680,-65),18446744073709551680)"
          , "([233,128512,955,66,65],[128512,955,66],True)"
          ]
    first <- run environment project (Just "bytecode") 900 command
    assertSuccess first
    assertEqual "independent small model" expected (out first)
    audit <- readJson (output </> "audit.json")
    assertBool "strict original Core accepted" (bool $ field audit "accepted")
    assertEqual "no missing definitions" [] (array $ field audit "missingGlobals")
    plan <- readJson (output </> "native/cache/plan.json")
    let component = case filter (\candidate ->
          string (field candidate "pkg-name") == "run-library-memory" &&
          string (field candidate "component-name") == "exe:library-memory") (objects plan "install-plan") of
            [candidate] -> candidate
            _ -> error "expected one library-memory executable"
        executable = string (field component "bin-file")
        rawEntry = string (field component "id") ++ ":Main.main"
    native <- runExe environment project Nothing 60 executable []
    assertSuccess native
    assertEqual "native model" expected (out native)
    forM_ ["ast", "bytecode"] $ \backend -> forM_ [False, True] $ \dense -> do
      let entry = if backend == "ast"
            then ["--run-io", '@' : (output </> "packages.json"), rawEntry]
            else ["--run-executable", '@' : (output </> "packages.json"), "main::Main.main",
                  "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"]
      result <- runExe environment project (Just backend) 180 "env"
        (["THC_OPTS=-Dthc.handoffSlabs=" ++ if dense then "true" else "false", runtime environment] ++ entry)
      assertSuccess result
      assertEqual (backend ++ "/dense=" ++ show dense) (out native) (out result)
