-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import qualified Data.ByteString as BS
import System.Directory (doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (exitFailure)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Counts (..), Test (..), assertBool, assertEqual, runTestTT)
import TestSupport

main :: IO ()
main = do
  environment <- setup
  counts <- runTestTT (fixture environment)
  if errors counts + failures counts == 0 then pure () else exitFailure

fixture :: Env -> Test
fixture environment = TestLabel "original System.IO executable lifecycle" $ TestCase $
  withFixtureNamed environment "test/fixtures/run-file-lifecycle" "file lifecycle" $ \project -> do
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = takeDirectory project </> "output"
        path = project </> "lifecycle.txt"
        missing = project </> "missing.txt"
        expectedBytes = BS.pack [0xce, 0xbb, 0xc3, 0xa9, 0x0a, 0xf0, 0x9d, 0x84, 0x9e, 0x0a]
        -- GHC's generated wrapper reaches signal startup, whose general
        -- fork# path is currently supported by the bytecode backend only.
        invoke = run environment project (Just "bytecode") 900 $
          ["run", project, "--exe", "lifecycle", "--installed-core", "required",
           "--thc-root", thcRoot environment,
           "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\compilerPath -> ["--with-ghc", compilerPath]) installedGhc ++
          maybe [] (\packagePath -> ["--with-ghc-pkg", packagePath]) installedPkg ++
          maybe [] (\sourcePath -> ["--ghc-source", sourcePath]) ghcSource
        planEntry plan = case filter (\component ->
          string (field component "pkg-name") == "run-file-lifecycle" &&
          string (field component "component-name") == "exe:lifecycle")
          (objects plan "install-plan") of
            [component] -> component
            _ -> error "expected one lifecycle executable in the Cabal plan"
    initiallyPresent <- doesFileExist path
    assertBool "fixture starts without a previous output file" (not initiallyPresent)
    missingBefore <- doesFileExist missing
    assertBool "missing-path control starts absent" (not missingBefore)
    result <- invoke
    assertSuccess result
    assertEqual "normal-exit stdout without newline" "file lifecycle ok" (out result)
    assertEqual "guest UTF-8 file" expectedBytes =<< BS.readFile path
    missingAfter <- doesFileExist missing
    assertBool "caught missing-path open did not create a file" (not missingAfter)
    audit <- readJson (output </> "audit.json")
    assertBool "strict original Core accepted" (bool $ field audit "accepted")
    assertEqual "no unresolved definitions" [] (array $ field audit "missingGlobals")
    plan <- readJson (output </> "native/cache/plan.json")
    let entry = planEntry plan
        mainRoot = "main::Main.main"
        shutdownRoot = "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"
    assertEqual "main and original shutdown are audited together"
      [mainRoot, shutdownRoot] (strings $ field audit "roots")
    removeFile path
    native <- runExe environment project Nothing 60 (string $ field entry "bin-file") []
    assertSuccess native
    assertEqual "native and THC stdout" (out native) (out result)
    assertEqual "native and THC UTF-8 file" expectedBytes =<< BS.readFile path
    removeFile path
