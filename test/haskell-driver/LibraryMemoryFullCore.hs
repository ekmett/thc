-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
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
fixture environment = TestLabel "original array and bytestring memory calls" $ TestCase $
  withFixtureNamed environment "test/fixtures/run-library-memory" "library memory" $ \project -> do
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = scratch environment </> "library-memory-full-core"
        command = ["run", project, "--exe", "library-memory", "--installed-core", "required",
          "--thc-root", thcRoot environment, "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\path -> ["--with-ghc", path]) installedGhc ++
          maybe [] (\path -> ["--with-ghc-pkg", path]) installedPkg ++
          maybe [] (\path -> ["--ghc-source", path]) ghcSource
        expected = "([2,3,5,7],[2,88,5,7])\n[65,66]\n"
    first <- run environment project (Just "bytecode") 900 command
    assertSuccess first
    assertEqual "independent small model" expected (out first)
    audit <- readJson (output </> "audit.json")
    assertBool "strict original Core accepted" (bool $ field audit "accepted")
    assertEqual "no missing definitions" [] (array $ field audit "missingGlobals")
    let symbols = map (string . flip field "symbol") (array $ field audit "foreignCalls")
    assertBool "original array memcpy retained" ("memcpy" `elem` symbols)
    assertBool "original bytestring strlen retained" ("strlen" `elem` symbols)
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
