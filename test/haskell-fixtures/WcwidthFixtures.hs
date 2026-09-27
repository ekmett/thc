-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : WcwidthFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; fixture compiler and host process services
--
-- Fixture acquisition support for wcwidth.
module WcwidthFixtures (prepareWcwidth) where

import Control.Monad (forM, forM_, unless)
import Data.Aeson (eitherDecodeStrict', Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (sort)
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv, unsetEnv)
import System.FilePath
import THC.Driver.GhcProxy (ghcProxyCommand)
import THC.Driver.PackageNative (finishPackageNative)

prepareWcwidth :: FilePath -> IO ()
prepareWcwidth root = do
  unsetEnv "GHC_ENVIRONMENT"
  let relative = "build/wcwidth"
      output = root </> relative
      fixture = "test/fixtures/run-wcwidth"
      execute = runLogged 600 root (relative </> "logs")
      unit = "wcwidth-ffi-0.1.0.0-inplace"
  createDirectoryIfMissing True output
  tasty <- maybe (fail "wcwidth requires original tasty-1.5.4 via THC_TASTY_SOURCE") canonicalizePath
    =<< lookupEnv "THC_TASTY_SOURCE"
  let declaration = "foreign import capi safe \"wchar.h wcwidth\" wcwidth :: CWchar -> CInt"
      originalSource = tasty </> "Test/Tasty/Ingredients/ConsoleReporter.hs"
  original <- BS.readFile originalSource
  unless (BSC.pack declaration `elem` BSC.lines original) (fail "original Tasty wcwidth declaration differs")
  unless ("        -1 -> 1  -- many chars have \"undefined\" width; default to 1 for these." `elem` BSC.lines original)
    (fail "original Tasty width fallback differs")
  BS.writeFile (output </> "ConsoleReporter.hs") original
  copyFile (tasty </> "LICENSE") (output </> "TASTY-LICENSE")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  python <- maybe "python3" id <$> lookupEnv "THC_PYTHON"
  built <- execute "driver-build" [] cabal ["build","--offline","-j2","exe:thc","lib:thc","exe:thc-interface"]
  driver <- locate execute cabal "exe:thc"
  helper <- locate execute cabal "exe:thc-interface"
  driverHash <- hashFile driver
  let key = take 16 driverHash
      native = output </> ("native-" ++ key)
      capture = output </> ("capture-" ++ key)
      pieces = output </> ("pieces-" ++ key)
      wrapper = output </> ("ghc-proxy-" ++ key) <.> "sh"
  libdir <- line . commandStdout <$> execute "ghc-libdir" [] ghc ["--print-libdir"]
  registry <- either fail pure . eitherDecodeStrict' . commandStdout =<< execute "plugin-unit" [] python
    [root </> "compiler/plugin.py","--root",root,"--ghc-pkg",ghcPkg,"--registry-only"]
  plugin <- field registry "unitId"
  pluginDb <- field registry "packageDb"
  writeFile wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
  permissions <- getPermissions wrapper
  setPermissions wrapper permissions {executable=True}
  let environment = [("THC_PROXY_DRIVER",driver),("THC_PROXY_ROOT",root),("THC_PROXY_GHC",ghc),
        ("THC_PROXY_GLOBAL_UNITS",unit),("THC_PROXY_CAPTURE",capture),("THC_PROXY_PLUGIN_DB",pluginDb),
        ("THC_PROXY_PLUGIN_UNIT",plugin),("THC_PROXY_INTERFACE_HELPER",helper),
        ("THC_PROXY_INTERFACE_LIBDIR",libdir),("THC_PROXY_NATIVE_PIECES",pieces)]
  acquired <- execute "acquisition" environment cabal ["build","--offline",
    "--project-file=" ++ root </> fixture </> "cabal.project","--builddir=" ++ native,
    "--with-compiler=" ++ wrapper,"exe:oracle"]
  plan <- readJson (native </> "cache/plan.json")
  planned <- field plan "install-plan" :: IO [Value]
  binaries <- concat <$> forM planned (\value -> case value of
    Object fields | KM.lookup "component-name" fields == Just "exe:oracle" -> (:[]) <$> field value "bin-file"
    _ -> pure [])
  binary <- case binaries of [value] -> pure value; _ -> fail "expected one wcwidth native oracle"
  oracles <- forM ["C","C.UTF-8"] $ \locale -> do
    oracle <- execute ("native-" ++ locale) [("LC_ALL",locale)] binary []
    unless (length (BSC.lines (commandStdout oracle)) == 12) (fail "wcwidth native row inventory differs")
    BS.writeFile (output </> locale <.> "tsv") (commandStdout oracle)
    pure oracle
  paths <- sort . filter ((== ".json") . takeExtension) <$> files (capture </> unit </> "core")
  modules <- mapM (\path -> (,) (takeFileName path) <$> BS.readFile path) paths
  unless (map fst modules == ["Width.json"]) (fail "wcwidth Core inventory differs")
  linked <- finishPackageNative pieces (capture </> unit) unit Nothing modules
  forM_ linked $ \(name,bytes) -> BS.writeFile (output </> name) bytes
  audits <- forM ["rawWidth","displayWidth"] $ \entry -> execute ("audit-" ++ entry) [] "python3"
    ["scripts/audit-core.py","--entry",unit ++ ":Width." ++ entry,
     "--output",output </> entry <.> "json",output </> "Width.json"]
  inputs <- hashes root ([fixture </> name | name <- ["cabal.project","wcwidth-ffi.cabal","src/Width.hs","app/Main.hs"]] ++
    ["test/haskell-fixtures/WcwidthFixtures.hs","compiler/plugin.py","src/THC/Driver/PackageNative.hs",
     "src/THC/Driver/NativeArgumentBridge.hs","src/THC/Driver/NativeLibrarySources.hs",
     "compiler/THC/ForeignImportProvenance.hs","scripts/audit-core.py","scripts/core_package_manifest.py"])
  artifacts <- hashes root ([relative </> name | name <-
    ["Width.json","rawWidth.json","displayWidth.json","C.tsv","C.UTF-8.tsv","ConsoleReporter.hs","TASTY-LICENSE"]] ++
    concatMap commandArtifacts ([built,acquired] ++ oracles ++ audits))
  writeJson (output </> "manifest.json") (object ["schema" .= (1::Int),"unit" .= unit,
    "driverSha256" .= driverHash,"inputHashes" .= inputs,"artifactHashes" .= artifacts,
    "commands" .= map commandRecord ([built,acquired] ++ oracles ++ audits)])
  putStrLn "wcwidth: original Tasty declaration/fallback, 24 locale-sensitive native observations, strict Core audits"
  where
    line bytes = case BSC.lines bytes of [value] -> BSC.unpack value; _ -> error "expected exactly one tool result"
    locate execute cabal target = line . commandStdout <$> execute ("locate-" ++ drop 4 target) [] cabal ["list-bin","--offline",target]
    files directory = do
      names <- sort <$> listDirectory directory
      concat <$> forM names (\name -> do
        let path = directory </> name
        nested <- doesDirectoryExist path
        if nested then files path else pure [path])
