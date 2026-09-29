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
import Data.Aeson (eitherDecodeStrict', Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
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

-- Real Cabal/GHC acquisition: ordinary native dependencies link by default,
-- while unsupported calling conventions retain their declaration obligations.
preparePackageNativeArchives :: FilePath -> IO ()
preparePackageNativeArchives root = do
  unsetEnv "GHC_ENVIRONMENT"
  let relative = "build/native-archive"
      output = root </> relative
      fixture = "t/fixtures/run-native-archive"
      execute = runLogged 600 root (relative </> "logs")
      mixedUnit = "native-archive-mixed-0.1.0.0-inplace"
      unresolvedUnit = "native-archive-unresolved-0.1.0.0-inplace"
      poisonedUnit = "native-archive-poisoned-0.1.0.0-inplace"
      providerUnit = "native-archive-provider-0.1.0.0-inplace"
      units = [mixedUnit,unresolvedUnit,poisonedUnit,providerUnit]
  createDirectoryIfMissing True output
  suppliedSupport <- lookupEnv "THC_PACKAGE_NATIVE_SUPPORT" >>= maybe
    (fail "Set THC_PACKAGE_NATIVE_SUPPORT to an existing genuine exception-runtime package manifest") canonicalizePath
  supportManifest <- readJson suppliedSupport
  bridge <- field supportManifest "foreignExceptionBridgeUnit"
  supportUnits <- field supportManifest "units" :: IO [Value]
  let visit seen [] = pure seen
      visit seen (unit:rest)
        | unit `elem` seen = visit seen rest
        | otherwise = case [value | value@(Object fields) <- supportUnits, KM.lookup "id" fields == Just (toJSON unit)] of
            [value] -> do
              dependencies <- field value "depends"
              visit (unit:seen) (dependencies ++ rest)
            _ -> fail "exception support has a missing or duplicate dependency"
  -- The selected GHC's implementation unit has its own Core identity, separate
  -- from the installed ghc-internal registration used in Cabal dependencies.
  selected <- visit [] [bridge :: String,"ghc-internal"]
  let support = output </> "runtime-support.json"
  case supportManifest of
    Object fields -> writeJson support (Object (KM.insert "units" (toJSON
      [value | value@(Object unit) <- supportUnits, KM.lookup "id" unit `elem` map (Just . toJSON) selected]) fields))
    _ -> fail "invalid exception support manifest"
  supportHash <- hashFile support
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  python <- maybe "python3" id <$> lookupEnv "THC_PYTHON"
  clang <- maybe "clang" id <$> lookupEnv "THC_CLANG"
  ar <- maybe "llvm-ar" id <$> lookupEnv "THC_LLVM_AR"
  _ <- execute "native-dependency-compile" [] clang ["-fPIC","-fembed-bitcode","-c",root </> fixture </> "provider/dependency.c",
    "-o",output </> "dependency.o"]
  _ <- execute "native-dependency-archive" [] ar ["rcs",output </> "libthc_archive_dependency.a",output </> "dependency.o"]
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
    [root </> "bin/plugin.py","--root",root,"--ghc-pkg",ghcPkg,"--registry-only"]
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
    "--extra-lib-dirs=" ++ output,
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
    paths <- sort . filter ((== ".cbd") . takeExtension) <$> files (capture </> unit </> "core")
    modules <- forM paths $ \path -> do
      let destination = output </> "linked" </> unit </> takeFileName path
      createDirectoryIfMissing True (takeDirectory destination)
      copyFile path destination
      pure (takeFileName path,destination)
    products <- finishPackageNative pieces (capture </> unit) unit Nothing modules
    pure [makeRelative root path | (_,path) <- products])
  let mixed = mixedUnit ++ ":Mixed."
      acceptedEntries = [mixed ++ "allowed", mixedUnit ++ ":Narrow.allowed",
        mixedUnit ++ ":CapiMix.mixedProbe#", mixedUnit ++ ":Lifecycle.lifecycleProbe#",
        unresolvedUnit ++ ":Unresolved.partialProbe#", unresolvedUnit ++ ":Unresolved.process",
        unresolvedUnit ++ ":Unresolved.throughGlobal", poisonedUnit ++ ":Poisoned.poisoned",
        providerUnit ++ ":Provider.nativeMath"]
      rejectedEntries = [mixed ++ "blocked", mixedUnit ++ ":Unknown.other",
        mixedUnit ++ ":Narrow.narrow", mixedUnit ++ ":Wide.wide"]
      audit entries label = ["bin/audit-core.py","--package-manifest",support,
        "--output",output </> label <.> "json"] ++
        concatMap (\entry -> ["--entry",entry]) entries ++ map (root </>) linked
  accepted <- execute "supported-audit" [] "python3" (audit acceptedEntries "supported-audit")
  rejected <- runLoggedExpect 1 180 root (relative </> "logs") "rejected-audit" [] "python3"
    (audit rejectedEntries "rejected-audit")
  report <- readJson (output </> "rejected-audit.json")
  ok <- field report "accepted"
  unless (not ok) (fail "archive-only reachable import passed its audit")
  sources <- files (root </> fixture)
  inputs <- hashes root (map (makeRelative root) sources ++
    ["t/haskell-fixtures/PackageNativeArchiveFixtures.hs","bin/plugin.py","src/driver/THC/Driver/PackageNative.hs",
     "src/driver/THC/Driver/NativeArgumentBridge.hs",
     "src/driver/THC/Driver/NativeDependencies.hs",
     "src/driver/THC/Driver/NativeLibrarySources.hs",
     "bin/core_package_manifest.py","bin/audit-core.py"])
  artifacts <- hashes root (linked ++ [relative </> name <.> "json" | name <-
    ["supported-audit","rejected-audit"]])
  writeJson (output </> "manifest.json") (object ["schema" .= (1::Int),"driverSha256" .= driverHash,
    "modules" .= linked,"packageManifest" .= support,"packageManifestSha256" .= supportHash,"inputHashes" .= inputs,"artifactHashes" .= artifacts,
    "mixedHeaderObservations" .= observations,
    "partialObservations" .= ([1010,1515,1313,1414,1818] :: [Int]),
    "commands" .= map commandRecord [built,acquired,oracle,accepted,rejected]])
  putStrLn "package-native-archives: supported mixed imports admitted; ordinary native libraries linked; interruptible, non-static and conflicting-width imports remain explicit"
  where
    line bytes = case BSC.lines bytes of [value] -> BSC.unpack value; _ -> error "expected one tool result"
    locate execute cabal target = line . commandStdout <$> execute ("locate-" ++ drop 4 target) [] cabal ["list-bin","--offline",target]
    files directory = do
      names <- sort <$> listDirectory directory
      concat <$> forM names (\name -> do
        let path = directory </> name
        nested <- doesDirectoryExist path
        if nested then files path else pure [path])
