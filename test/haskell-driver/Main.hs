-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Main (main) where

import System.Exit (exitFailure)
import Test.HUnit (Test(..), Counts(..), runTestTT)
import qualified PlanTests
import qualified RunTests
import qualified ProjectTests
import qualified StoreProjectTests
import qualified EmptyStoreProjectTests
import qualified ScalarBitcodeTests
import qualified NativeRecipeTests
import qualified InstalledForeignTests
import qualified TestSupportTests
import TestSupport (setup)

main :: IO ()
main = do
  env <- setup
  counts <- runTestTT $ TestList
    [ InstalledForeignTests.tests
    , ScalarBitcodeTests.tests
    , NativeRecipeTests.tests
    , InstalledForeignTests.viewTests env
    , TestSupportTests.tests
    , PlanTests.tests env
    , RunTests.tests env
    , ProjectTests.tests env
    , StoreProjectTests.tests env
    , EmptyStoreProjectTests.tests env
    ]
  if errors counts + failures counts == 0 then pure () else exitFailure
