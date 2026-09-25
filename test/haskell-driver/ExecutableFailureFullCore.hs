-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import qualified Data.ByteString.Char8 as BS
import Data.List (find, isPrefixOf, tails)
import System.Directory (doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode (..), exitFailure)
import System.FilePath ((</>), takeDirectory)
import Test.HUnit (Counts (..), Test (..), assertBool, assertEqual, runTestTT)
import qualified Test.HUnit as HUnit
import TestSupport

main :: IO ()
main = do
  environment <- setup
  counts <- runTestTT (fixture environment)
  if errors counts + failures counts == 0 then pure () else exitFailure

fixture :: Env -> Test
fixture environment = TestLabel "original executable uncaught IO exception" $ TestCase $
  withFixtureNamed environment "test/fixtures/run-executable-failure" "executable failure" $ \project -> do
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = takeDirectory project </> "output"
        path = project </> "failure.txt"
        invoke = run environment project (Just "bytecode") 900 $
          ["run", project, "--exe", "failure", "--installed-core", "required",
           "--thc-root", thcRoot environment,
           "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\compilerPath -> ["--with-ghc", compilerPath]) installedGhc ++
          maybe [] (\packagePath -> ["--with-ghc-pkg", packagePath]) installedPkg ++
          maybe [] (\sourcePath -> ["--ghc-source", sourcePath]) ghcSource
        planEntry plan = case filter (\component ->
          string (field component "pkg-name") == "run-executable-failure" &&
          string (field component "component-name") == "exe:failure")
          (objects plan "install-plan") of
            [component] -> component
            _ -> error "expected one failure executable in the Cabal plan"
    present <- doesFileExist path
    assertBool "fixture starts without an output file" (not present)
    result <- invoke
    assertFailure result
    assertEqual "stdout flushed on uncaught exception" "stdout before failure" (out result)
    assertEqual "bracket closed and flushed file" (BS.pack "file before failure") =<< BS.readFile path
    audit <- readJson (output </> "audit.json")
    assertBool "strict original Core accepted" (bool $ field audit "accepted")
    assertEqual "no unresolved definitions" [] (array $ field audit "missingGlobals")
    assertEqual "no audit issues" [] (array $ field audit "issues")
    assertEqual "original main and shutdown roots"
      ["main::Main.main", "ghc-internal:GHC.Internal.TopHandler.flushStdHandles"]
      (strings $ field audit "roots")
    plan <- readJson (output </> "native/cache/plan.json")
    removeFile path
    native <- runExe environment project Nothing 60
      (string $ field (planEntry plan) "bin-file") []
    assertFailure native
    assertEqual "native exit status" (ExitFailure 1) (code native)
    assertEqual "driver exit status" (code native) (code result)
    assertEqual "native and THC stdout" (out native) (out result)
    assertEqual "native and THC flushed file" (BS.pack "file before failure") =<< BS.readFile path
    nativeReport <- originalReport (err native)
    guestReport <- originalReport (err result)
    assertContains "user error (THC expected uncaught failure)" (err native)
    assertBool "original TopHandler report matches native GHC after executable-name prefix"
      (nativeReport `isPrefixOf` guestReport)
    assertContains ("command failed: " ++ runtime environment ++
                    " (" ++ show (code native) ++ ")") (err result)
    removeFile path

-- The driver can append its own nonzero-command report, and the native binary
-- and JVM launcher have different process names. Keep the original exception
-- report byte-for-byte equal after that one process-name prefix.
originalReport :: String -> IO String
originalReport output = case find (isPrefixOf ": Uncaught exception ") (tails output) of
  Just report -> pure report
  Nothing -> HUnit.assertFailure "missing original TopHandler exception report" >> pure ""
