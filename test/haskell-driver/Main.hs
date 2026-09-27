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
-- Select and run the Haskell driver test groups.
module Main (main) where

import System.Environment (getArgs)
import System.Exit (die, exitFailure)
import Test.HUnit (Test(..), Counts(..), runTestTT)
import qualified PlanTests
import qualified CoreIndexTests
import qualified CoreSymbolsTests
import qualified RunTests
import qualified RunOptionsTests
import qualified BenchmarkTests
import qualified ProjectTests
import qualified StoreProjectTests
import qualified EmptyStoreProjectTests
import qualified ScalarBitcodeTests
import qualified PackageNativeTests
import qualified NativeCacheTests
import qualified NativeRecipeTests
import qualified RuntimeShimTests
import qualified InstalledForeignTests
import qualified InstalledHydrationTests
import qualified TestSupportTests
import TestSupport (setup)

main :: IO ()
main = do
  arguments <- getArgs
  case InstalledHydrationTests.helperMode arguments of
    Just action -> action
    Nothing -> runTests arguments

runTests :: [String] -> IO ()
runTests arguments = do
  env <- setup
  selected <- case arguments of
    ["--installed-foreign-source-only"] -> pure
      [InstalledForeignTests.tests, InstalledForeignTests.viewTests env, InstalledForeignTests.sourceTests env]
    ["--installed-hydration-only"] -> pure [InstalledHydrationTests.tests]
    ["--core-index-only"] -> pure [CoreIndexTests.tests, CoreSymbolsTests.tests env]
    ["--runnable-targets-only"] -> pure [RunOptionsTests.tests env, BenchmarkTests.tests env]
    ["--acquire-project-only"] -> pure [ProjectTests.acquisitionTests env]
    ["--exception-bridge-only"] -> pure [RuntimeShimTests.tests, ProjectTests.exceptionBridgeTests env]
    ["--runtime-shim-only"] -> pure [RuntimeShimTests.tests]
    ["--run-options-only"] -> pure [RunOptionsTests.tests env]
    ["--run-ffi-only"] -> pure [RunOptionsTests.tests env, RunTests.tests env]
    ["--store-inventory-only"] -> pure [EmptyStoreProjectTests.tests env]
    ["--store-projects-only"] -> pure [StoreProjectTests.tests env]
    ["--export-safety-only"] -> pure [StoreProjectTests.exportSafetyTests env]
    ["--inplace-store-only"] -> pure [StoreProjectTests.inplaceTests env]
    ["--concurrent-store-only"] -> pure [StoreProjectTests.concurrentTests env]
    ["--package-native-only"] -> pure [PackageNativeTests.tests]
    [] -> pure
      [ CoreIndexTests.tests
      , CoreSymbolsTests.tests env
      , InstalledForeignTests.tests
      , InstalledHydrationTests.tests
      , ScalarBitcodeTests.tests
      , PackageNativeTests.tests
      , NativeCacheTests.tests
      , NativeRecipeTests.tests
      , RuntimeShimTests.tests
      , InstalledForeignTests.viewTests env
      , TestSupportTests.tests
      , PlanTests.tests env
      , RunTests.tests env
      , RunOptionsTests.tests env
      , BenchmarkTests.tests env
      , ProjectTests.tests env
      , StoreProjectTests.tests env
      , EmptyStoreProjectTests.tests env
      ]
    _ -> die "Usage: driver-tests [--core-index-only|--runnable-targets-only|--acquire-project-only|--run-options-only|--run-ffi-only|--store-inventory-only|--store-projects-only|--export-safety-only|--inplace-store-only|--concurrent-store-only|--installed-hydration-only|--installed-foreign-source-only|--package-native-only]"
  counts <- runTestTT $ TestList selected
  if errors counts + failures counts == 0 then pure () else exitFailure
