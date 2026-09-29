-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Module      : JavaArrayFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC
--
-- Export direct Java-array loop and scalar-carrier checks.
{-# LANGUAGE OverloadedStrings #-}
module JavaArrayFixtures (prepareJavaArrays) where
import Data.Aeson (Value(..), toJSON)
import qualified Data.Aeson.KeyMap as KM
import FixtureSupport (run, writeJson)
import InstalledCoreFixtures (field, readJson)
import THC.Driver.CoreSymbols (publishCoreUnit)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.FilePath ((</>))

prepareJavaArrays :: FilePath -> IO ()
prepareJavaArrays root = do
  let output = root </> "build/java-arrays"
  createDirectoryIfMissing True output
  _ <- run root [("THC_CORE_OUT", output </> "core"), ("THC_GHC_OUT", output </> "ghc")]
    "bin/export-core.sh" ["-fplugin-opt=THC.Plugin:post-tidy", "-isrc/examples", "-it/fixtures/compiler",
      "src/runtime/THC/Exception.hs", "t/fixtures/compiler/JavaArrays.hs"] ""
  packages <- maybe (root </> "build/foreign-exceptions/installed/packages.json") id
    <$> lookupEnv "THC_FOREIGN_EXCEPTION_INSTALLED"
  manifest <- readJson packages
  units <- field manifest "units" :: IO [Value]
  -- Reuse production publication and its cache. The test opens the immutable
  -- directory and decodes genuine GHC bindings only when demanded.
  indexed <- mapM (publishCoreUnit (output </> "cache") False) units
  case manifest of
    Object fields -> writeJson (output </> "packages.json") $ Object $
      KM.insert "foreignExceptionBridgeUnit" (toJSON ("main" :: String)) $
      KM.insert "units" (toJSON indexed) fields
    _ -> fail "Invalid installed package manifest"
  putStrLn "Prepared Java primitive-array and vector Haskell loops"
