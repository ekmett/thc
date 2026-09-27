-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : PackageNativeArchiveFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for package native archive.
module PackageNativeArchiveFixtures (preparePackageNativeArchives) where

import Control.Monad (forM, unless)
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
import Text.Read (readMaybe)
import THC.Driver.GhcProxy (ghcProxyCommand)
import THC.Driver.PackageNative (finishPackageNative)

-- Real Cabal/GHC acquisition with known unsupported declarations. The native
-- oracle executes them; guest admission must not. No invented LLVM or proof.
preparePackageNativeArchives :: FilePath -> IO ()
preparePackageNativeArchives root = do
  unsetEnv "GHC_ENVIRONMENT"
  let relative = "build/native-archive"
      output = root </> relative
      fixture = "test/fixtures/run-native-archive"
      execute = runLogged 600 root (relative </> "logs")
      mixedUnit = "native-archive-mixed-0.1.0.0-inplace"
      unresolvedUnit = "native-archive-unresolved-0.1.0.0-inplace"
      poisonedUnit = "native-archive-poisoned-0.1.0.0-inplace"
      providerUnit = "native-archive-provider-0.1.0.0-inplace"
      units = [mixedUnit,unresolvedUnit,poisonedUnit,providerUnit]
  createDirectoryIfMissing True output
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
        ("THC_PROXY_GLOBAL_UNITS",unlines units),("THC_PROXY_CAPTURE",capture),("THC_PROXY_PLUGIN_DB",pluginDb),
        ("THC_PROXY_PLUGIN_UNIT",plugin),("THC_PROXY_INTERFACE_HELPER",helper),
        ("THC_PROXY_INTERFACE_LIBDIR",libdir),("THC_PROXY_NATIVE_PIECES",pieces)]
  acquired <- execute "acquisition" environment cabal ["build","--offline",
    "--project-file=" ++ root </> fixture </> "cabal.project","--builddir=" ++ native,
    "--with-compiler=" ++ wrapper,"exe:oracle"]
  plan <- readJson (native </> "cache/plan.json")
  planned <- field plan "install-plan" :: IO [Value]
  executables <- concat <$> forM planned (\value -> case value of
    Object fields | KM.lookup "component-name" fields == Just "exe:oracle" -> (:[]) <$> field value "bin-file"
    _ -> pure [])
  oracle <- case executables of
    [binary] -> execute "native-oracle" [] binary []
    _ -> fail "expected one actual native archive oracle"
  let oracleLines = BSC.lines (commandStdout oracle)
  unless (take 7 oracleLines == ["40","99","1","7","True","True","47"]) (fail "native archive oracle differs")
  observations <- case drop 7 oracleLines of
    [row,"1010","1515","1313","1414","1818","True","True"] -> maybe (fail "native mixed-header oracle is malformed") pure
      (readMaybe (BSC.unpack row) :: Maybe [(Integer,Integer,Integer,Integer,Integer,Integer)])
    _ -> fail "missing native mixed-header oracle"
  unless (length observations == 36) (fail "native mixed-header oracle row count differs")
  unless (all (\(_,_,typed,wide,_,staticPointer) -> typed == wide && typed == staticPointer) observations)
    (fail "native typed and machine-register argument calls differ")
  linked <- concat <$> forM units (\unit -> do
    paths <- sort . filter ((== ".json") . takeExtension) <$> files (capture </> unit </> "core")
    modules <- mapM (\path -> (,) (takeFileName path) <$> BS.readFile path) paths
    products <- finishPackageNative pieces (capture </> unit) unit Nothing modules
    forM products $ \(name,bytes) -> do
      let path = relative </> "linked" </> unit </> name
      createDirectoryIfMissing True (takeDirectory (root </> path))
      BS.writeFile (root </> path) bytes
      pure path)
  let audit entry label = ["scripts/audit-core.py","--entry",entry,"--output",output </> label <.> "json"] ++ map (root </>) linked
      mixed = mixedUnit ++ ":Mixed."
  accepted <- execute "supported-audit" [] "python3" (audit (mixed ++ "allowed") "supported-audit")
  mixedWidth <- execute "mixed-width-audit" [] "python3"
    (audit (mixedUnit ++ ":Narrow.allowed") "mixed-width-audit")
  mixedHeader <- execute "mixed-header-audit" [] "python3"
    (audit (mixedUnit ++ ":CapiMix.mixedProbe#") "mixed-header-audit")
  lifecycle <- execute "lifecycle-audit" [] "python3"
    (audit (mixedUnit ++ ":Lifecycle.lifecycleProbe#") "lifecycle-audit")
  partial <- execute "partial-audit" [] "python3"
    (audit (unresolvedUnit ++ ":Unresolved.partialProbe#") "partial-audit")
  negatives <- forM [(mixed ++ "blocked","interruptible"),(mixedUnit ++ ":Unknown.other","non-static"),
    (mixedUnit ++ ":Narrow.narrow","narrow-conflict"),(mixedUnit ++ ":Wide.wide","wide-conflict"),
    (unresolvedUnit ++ ":Unresolved.process","unresolved"),
    (unresolvedUnit ++ ":Unresolved.throughGlobal","indirect-unresolved"),
    (poisonedUnit ++ ":Poisoned.poisoned","constructor-unresolved"),
    (providerUnit ++ ":Provider.nativeMath","provider-container")] $ \(entry,label) -> do
      rejected <- runLoggedExpect 1 60 root (relative </> "logs") label [] "python3" (audit entry label)
      report <- readJson (output </> label <.> "json")
      ok <- field report "accepted"
      unless (not ok) (fail "archive-only reachable import passed its audit")
      pure rejected
  sources <- files (root </> fixture)
  inputs <- hashes root (map (makeRelative root) sources ++
    ["test/haskell-fixtures/PackageNativeArchiveFixtures.hs","compiler/plugin.py","src/THC/Driver/PackageNative.hs",
     "src/THC/Driver/NativeArgumentBridge.hs",
     "src/THC/Driver/NativeLibrarySources.hs",
     "scripts/core_package_manifest.py","scripts/audit-core.py"])
  artifacts <- hashes root (linked ++ [relative </> name <.> "json" | name <-
    ["supported-audit","mixed-width-audit","mixed-header-audit","lifecycle-audit","partial-audit","interruptible","non-static","narrow-conflict","wide-conflict","unresolved","indirect-unresolved","constructor-unresolved","provider-container"]])
  writeJson (output </> "manifest.json") (object ["schema" .= (1::Int),"driverSha256" .= driverHash,
    "modules" .= linked,"inputHashes" .= inputs,"artifactHashes" .= artifacts,
    "mixedHeaderObservations" .= observations,
    "partialObservations" .= ([1010,1515,1313,1414,1818] :: [Int]),
    "commands" .= map commandRecord ([built,acquired,oracle,accepted,mixedWidth,mixedHeader,lifecycle,partial] ++ negatives)])
  putStrLn "package-native-archives: supported mixed imports admitted; interruptible, non-static, conflicting-width and unresolved imports archived and rejected when reachable"
  where
    line bytes = case BSC.lines bytes of [value] -> BSC.unpack value; _ -> error "expected one tool result"
    locate execute cabal target = line . commandStdout <$> execute ("locate-" ++ drop 4 target) [] cabal ["list-bin","--offline",target]
    files directory = do
      names <- sort <$> listDirectory directory
      concat <$> forM names (\name -> do
        let path = directory </> name
        nested <- doesDirectoryExist path
        if nested then files path else pure [path])
