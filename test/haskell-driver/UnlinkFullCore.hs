-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Monad (forM_)
import System.Directory (doesFileExist)
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
fixture environment = TestLabel "original directory.removeFile native comparison" $ TestCase $
  withFixtureNamed environment "test/fixtures/run-unlink" "unlink project" $ \project -> do
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = scratch environment </> "unlink-full-core"
        ownedFile = output </> "fixture-owned-file.tmp"
        command = ["run", project, "--exe", "run-unlink:exe:unlink", "--installed-core", "required",
          "--thc-root", thcRoot environment, "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\path -> ["--with-ghc", path]) installedGhc ++
          maybe [] (\path -> ["--with-ghc-pkg", path]) installedPkg ++
          maybe [] (\path -> ["--ghc-source", path]) ghcSource ++ ["--", ownedFile]
        absent = doesFileExist ownedFile >>= assertBool "owned file removed" . not
    first <- run environment project (Just "bytecode") 900 command
    assertSuccess first
    assertEqual "guest original directory behavior" "unlink ok\n" (out first)
    absent
    audit <- readJson (output </> "audit.json")
    assertBool "strict original Core accepted" (bool $ field audit "accepted")
    assertEqual "no missing definitions" [] (array $ field audit "missingGlobals")
    assertEqual "no audit issues" [] (array $ field audit "issues")
    plan <- readJson (output </> "native/cache/plan.json")
    let component = case filter (\candidate ->
          string (field candidate "pkg-name") == "run-unlink" &&
          string (field candidate "component-name") == "exe:unlink") (objects plan "install-plan") of
            [candidate] -> candidate
            _ -> error "expected one unlink executable"
        executable = string (field component "bin-file")
        rawEntry = string (field component "id") ++ ":Main.main"
    native <- runExe environment project Nothing 60 executable [ownedFile]
    assertSuccess native
    assertEqual "native GHC / THC" (out native) (out first)
    absent
    forM_ ["ast", "bytecode"] $ \backend -> forM_ [False, True] $ \dense -> do
      -- AST's separate process-signal startup limitation is not part of unlink.
      let entry = if backend == "ast"
            then ["--run-io", '@' : (output </> "packages.json"), rawEntry]
            else ["--run-executable", '@' : (output </> "packages.json"), "main::Main.main",
                  "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"]
      result <- runExe environment project (Just backend) 180 "env"
        (["THC_OPTS=-Dthc.handoffSlabs=" ++ if dense then "true" else "false", runtime environment] ++
          entry ++ ["--", "unlink", ownedFile])
      assertSuccess result
      assertEqual (backend ++ "/dense=" ++ show dense) (out native) (out result)
      absent
