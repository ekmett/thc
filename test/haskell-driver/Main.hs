-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import System.Environment (getArgs)
import System.Exit (die, exitFailure)
import Test.HUnit (Test(..), Counts(..), runTestTT)
import qualified PlanTests
import qualified RunTests
import qualified RunOptionsTests
import qualified ProjectTests
import qualified StoreProjectTests
import qualified EmptyStoreProjectTests
import qualified ScalarBitcodeTests
import qualified PackageNativeTests
import qualified NativeCacheTests
import qualified NativeRecipeTests
import qualified RuntimeShimTests
import qualified InstalledForeignTests
import qualified TestSupportTests
import TestSupport (setup)

main :: IO ()
main = do
  env <- setup
  arguments <- getArgs
  selected <- case arguments of
    ["--acquire-project-only"] -> pure [ProjectTests.acquisitionTests env]
    ["--run-options-only"] -> pure [RunOptionsTests.tests env]
    ["--run-ffi-only"] -> pure [RunOptionsTests.tests env, RunTests.tests env]
    ["--store-inventory-only"] -> pure [EmptyStoreProjectTests.tests env]
    [] -> pure
      [ InstalledForeignTests.tests
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
      , ProjectTests.tests env
      , StoreProjectTests.tests env
      , EmptyStoreProjectTests.tests env
      ]
    _ -> die "Usage: driver-tests [--acquire-project-only|--run-options-only|--run-ffi-only|--store-inventory-only]"
  counts <- runTestTT $ TestList selected
  if errors counts + failures counts == 0 then pure () else exitFailure
