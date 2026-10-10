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

import Control.Applicative ((<|>))
import System.Environment (getArgs)
import System.Exit (die, exitFailure)
import Test.HUnit (Test(..), Counts(..), runTestTT)
import qualified PlanTests
import qualified CoreIndexTests
import qualified CoreSymbolsTests
import qualified BundleSelectionTests
import qualified RunTests
import qualified PublicPackageTests
import qualified RunOptionsTests
import qualified PinnedFlagsTests
import qualified BenchmarkTests
import qualified BackpackTests
import qualified ProjectTests
import qualified StoreProjectTests
import qualified EmptyStoreProjectTests
import qualified ScalarBitcodeTests
import qualified PackageNativeTests
import qualified NativeCacheTests
import qualified NativeImageTests
import qualified NativeRecipeTests
import qualified RuntimeShimTests
import qualified InstalledForeignTests
import qualified InstalledHydrationTests
import qualified TestSupportTests
import TestSupport (setup)

main :: IO ()
main = do
  arguments <- getArgs
  case TestSupportTests.helperMode arguments <|> InstalledHydrationTests.helperMode arguments of
    Just action -> action
    Nothing -> runTests arguments

runTests :: [String] -> IO ()
runTests arguments = do
  env <- setup
  let unitTests =
        [ ProjectTests.installedProducerTests env
        , CoreIndexTests.tests
        , CoreSymbolsTests.tests env
        , BundleSelectionTests.tests env
        , InstalledForeignTests.tests
        , InstalledHydrationTests.tests
        , ScalarBitcodeTests.tests
        , NativeCacheTests.tests
        , NativeImageTests.tests
        , RuntimeShimTests.tests
        , TestSupportTests.tests
        , PlanTests.unitTests env
        , PinnedFlagsTests.tests
        , RunOptionsTests.tests env
        ]
  selected <- case arguments of
    ["--unit-only"] -> pure unitTests
    ["--installed-producer-only"] -> pure [ProjectTests.installedProducerTests env]
    ["--test-support-only"] -> pure [TestSupportTests.tests]
    ["--installed-view-only"] -> pure
      [InstalledForeignTests.tests, InstalledForeignTests.viewTests env]
    ["--installed-foreign-source-only"] -> pure
      [InstalledForeignTests.tests, InstalledForeignTests.viewTests env, InstalledForeignTests.sourceTests env]
    ["--configured-source-view-only"] -> pure
      [InstalledForeignTests.tests, InstalledForeignTests.viewTests env, InstalledForeignTests.configuredSourceViewTests env]
    ["--installed-hydration-only"] -> pure [InstalledHydrationTests.tests]
    ["--core-index-only"] -> pure [CoreIndexTests.tests, CoreSymbolsTests.tests env]
    ["--bundle-selection-only"] -> pure [BundleSelectionTests.tests env]
    ["--runnable-targets-only"] -> pure [RunOptionsTests.tests env, BenchmarkTests.tests env]
    ["--acquire-project-only"] -> pure [ProjectTests.acquisitionTests env]
    ["--build-project-only"] -> pure [ProjectTests.buildTests env]
    ["--backpack-full-core-only"] -> pure [BackpackTests.tests env]
    ["--interop-project-only"] -> pure
      [ProjectTests.interopTests env, StoreProjectTests.proxyOptionsTest env,
       StoreProjectTests.exportSafetyTests env, PackageNativeTests.tests]
    ["--ghc-proxy-only"] -> pure [StoreProjectTests.proxyOptionsTest env]
    ["--static-exports-only"] -> pure [StoreProjectTests.proxyOptionsTest env, StoreProjectTests.staticExportsTest env, PackageNativeTests.tests]
    ["--exception-bridge-only"] -> pure [RuntimeShimTests.tests, ProjectTests.exceptionBridgeTests env]
    ["--runtime-shim-only"] -> pure [RuntimeShimTests.tests]
    ["--pinned-flags-only"] -> pure [PinnedFlagsTests.tests]
    ["--run-options-only"] -> pure [RunOptionsTests.tests env]
    ["--run-ffi-only"] -> pure [RunOptionsTests.tests env, RunTests.tests env]
    ["--public-packages-only"] -> pure [PublicPackageTests.tests env]
    ["--cstring-project-only"] -> pure [ProjectTests.cstringTests env]
    ["--store-inventory-only"] -> pure [EmptyStoreProjectTests.tests env]
    ["--store-project-only"] -> pure [StoreProjectTests.storeProjectTest env]
    ["--native-variants-only"] -> pure [StoreProjectTests.nativeVariantsTest env False, StoreProjectTests.nativeVariantsTest env True]
    ["--store-projects-only"] -> pure [StoreProjectTests.tests env]
    ["--custom-store-only"] -> pure [StoreProjectTests.customStoreProjectTest env]
    ["--export-safety-only"] -> pure [StoreProjectTests.exportSafetyTests env]
    ["--inplace-store-only"] -> pure [StoreProjectTests.inplaceTests env]
    ["--concurrent-store-only"] -> pure [StoreProjectTests.concurrentTests env]
    ["--package-native-only"] -> pure [PackageNativeTests.tests]
    ["--native-image-only"] -> pure [NativeImageTests.tests, BundleSelectionTests.tests env, RunOptionsTests.tests env]
    ["--native-cache-only"] -> pure [NativeCacheTests.tests]
    ["--cache-directory-only"] -> pure [NativeCacheTests.cacheDirectoryTest]
    ["--native-recipe-only"] -> pure [NativeRecipeTests.tests, NativeRecipeTests.interfaceTests]
    ["--scalar-bitcode-only"] -> pure [ScalarBitcodeTests.tests]
    [] -> pure $ unitTests ++
      [ PackageNativeTests.tests
      , NativeRecipeTests.tests
      , NativeRecipeTests.interfaceTests
      , InstalledForeignTests.viewTests env
      , PlanTests.integrationTests env
      , RunTests.tests env
      , PublicPackageTests.tests env
      , BenchmarkTests.tests env
      , ProjectTests.tests env
      , StoreProjectTests.tests env
      , EmptyStoreProjectTests.tests env
      ]
    _ -> die "Usage: driver-tests [--unit-only|--test-support-only|--pinned-flags-only|--core-index-only|--bundle-selection-only|--runnable-targets-only|--acquire-project-only|--build-project-only|--backpack-full-core-only|--interop-project-only|--ghc-proxy-only|--static-exports-only|--run-options-only|--run-ffi-only|--public-packages-only|--cstring-project-only|--store-inventory-only|--store-project-only|--native-variants-only|--store-projects-only|--custom-store-only|--export-safety-only|--inplace-store-only|--concurrent-store-only|--installed-hydration-only|--installed-view-only|--installed-foreign-source-only|--package-native-only|--native-image-only|--native-cache-only|--cache-directory-only|--native-recipe-only|--scalar-bitcode-only]"
  counts <- runTestTT $ TestList selected
  if errors counts + failures counts == 0 then pure () else exitFailure
