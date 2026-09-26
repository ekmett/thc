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
fixture environment = TestLabel "original System.IO native binary buffers" $ TestCase $
  withFixtureNamed environment "test/fixtures/run-binary-buffers" "binary buffers" $ \project -> do
    installedGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
    installedPkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
    ghcSource <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
    let output = takeDirectory project </> "output"
        path = project </> "buffers.bin"
        expectedBytes = BS.pack [0x00, 0x7f, 0x80, 0xff, 0x01, 0xfe]
        -- Original executable signal startup currently requires bytecode fork#.
        invoke = run environment project (Just "bytecode") 900 $
          ["run", project, "--exe", "binary-buffers", "--installed-core", "required",
           "--thc-root", thcRoot environment,
           "--runtime", runtime environment, "--dist-dir", output] ++
          maybe [] (\compilerPath -> ["--with-ghc", compilerPath]) installedGhc ++
          maybe [] (\packagePath -> ["--with-ghc-pkg", packagePath]) installedPkg ++
          maybe [] (\sourcePath -> ["--ghc-source", sourcePath]) ghcSource
        planEntry plan = case filter (\component ->
          string (field component "pkg-name") == "run-binary-buffers" &&
          string (field component "component-name") == "exe:binary-buffers")
          (objects plan "install-plan") of
            [component] -> component
            _ -> error "expected one binary-buffers executable in the Cabal plan"
    present <- doesFileExist path
    assertBool "fixture starts without an output file" (not present)
    result <- invoke
    assertSuccess result
    assertEqual "free finalizer and successful byte checks"
      "buffer freed\nbinary buffers ok" (out result)
    assertEqual "guest NUL and high-bit file bytes" expectedBytes =<< BS.readFile path
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
    assertSuccess native
    assertEqual "native stderr" "" (err native)
    assertEqual "native and THC stdout" (out native) (out result)
    assertEqual "native and THC binary file" expectedBytes =<< BS.readFile path
    removeFile path
