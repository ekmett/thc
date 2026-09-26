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
fixture environment = TestLabel "original System.Environment argument lifecycle" $ TestCase $
  withFixtureNamed environment "test/fixtures/run-arguments" "arguments project" $ \project -> do
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = scratch environment </> "arguments-full-core"
        command = ["run", project, "--exe", "run-arguments:exe:arguments", "--installed-core", "required",
          "--thc-root", thcRoot environment, "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\path -> ["--with-ghc", path]) installedGhc ++
          maybe [] (\path -> ["--with-ghc-pkg", path]) installedPkg ++
          maybe [] (\path -> ["--ghc-source", path]) ghcSource
        arguments = ["alpha beta", "", "lambda-\x03bb", "--help", "--"]
    -- The first actual driver invocation produces the original installed-Core
    -- bundle and strict main/shutdown audit. Subsequent calls reuse that closure.
    first <- run environment project (Just "bytecode") 900 (command ++ ["--"] ++ arguments)
    assertSuccess first
    audit <- readJson (output </> "audit.json")
    assertBool "strict original Core accepted" (bool $ field audit "accepted")
    assertEqual "no missing definitions" [] (array $ field audit "missingGlobals")
    assertEqual "main and original shutdown roots"
      ["main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"]
      (strings $ field audit "roots")
    plan <- readJson (output </> "native/cache/plan.json")
    let component = case filter (\candidate ->
          string (field candidate "pkg-name") == "run-arguments" &&
          string (field candidate "component-name") == "exe:arguments") (objects plan "install-plan") of
            [candidate] -> candidate
            _ -> error "expected one arguments executable"
        executable = string (field component "bin-file")
        rawEntry = string (field component "id") ++ ":Main.main"
    native <- runExe environment project Nothing 60 executable arguments
    assertSuccess native
    assertEqual "driver arguments, native/THC" (out native) (out first)
    forM_ ["ast", "bytecode"] $ \backend -> forM_ [False, True] $ \dense ->
      forM_ [[], arguments] $ \guestArgs -> do
        reference <- runExe environment project Nothing 60 executable guestArgs
        assertSuccess reference
        let modeRuntime = runtime environment
            -- Original generated :Main startup still requires the bytecode-only
            -- process signal dispatcher. AST exercises the genuine raw IO main;
            -- the fixture explicitly flushes stdout in both entry conventions.
            entryArgs = if backend == "ast"
              then ["--run-io", '@' : (output </> "packages.json"), rawEntry]
              else ["--run-executable", '@' : (output </> "packages.json"), "main::Main.main",
                    "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"]
        -- The runtime launcher accepts the same opaque suffix as the driver;
        -- use it for matrix reruns without re-exporting unchanged packages.
        result <- runExe environment project (Just backend) 180 "env"
          (["THC_OPTS=-Dthc.handoffSlabs=" ++ if dense then "true" else "false", modeRuntime] ++
            entryArgs ++ ["--", "arguments"] ++ guestArgs)
        assertSuccess result
        assertEqual (backend ++ "/dense=" ++ show dense ++ "/" ++ show guestArgs)
          (out reference) (out result)
