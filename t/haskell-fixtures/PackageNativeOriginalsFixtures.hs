-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (166 package-native-originals)
-- Purpose: Exercise digest, erf and primitive through original package Core/native
--   linkage; compare checksum, floating and primitive-array observations.
-- Consumes: External digest/erf/primitive source trees, native toolchain, driver/plugin,
--   five Original* oracle/entry sources and package-declared native libraries.
-- Produces/consumed result: Captured/linked package CBDs, three native TSVs and manifest.
-- Cost and overlap: Public package smoke is sufficient for these libraries. This
--   bespoke three-package acquisition and 1078 rows are excess.
-- Build status: QUARANTINED. Copied-source leftovers require a fresh output directory;
--   staging also enumerates captured CBDs and fixes exact package-module inventories.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 166.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : PackageNativeOriginalsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for package native originals.
module PackageNativeOriginalsFixtures (preparePackageNativeOriginals) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (eitherDecodeStrict', Value, object, (.=))
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

-- Native observations use original public APIs. The JVM checks digest's six
-- foreign adapters and actual erf entry Core, without patched package sources.
preparePackageNativeOriginals :: FilePath -> IO ()
preparePackageNativeOriginals root = do
  unsetEnv "GHC_ENVIRONMENT"
  let relative = "build/original-native"
      output = root </> relative
      execute = runLogged 600 root (relative </> "logs")
  createDirectoryIfMissing True output
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  python <- maybe "python3" id <$> lookupEnv "THC_PYTHON"
  supplied <- maybe (output </> "digest-0.0.2.1") id <$> lookupEnv "THC_DIGEST_SOURCE"
  original <- canonicalizePath supplied
  let source = output </> "sources/digest-0.0.2.1"
  exists <- doesFileExist (original </> "digest.cabal")
  unless exists (fail "package-native-originals requires unchanged digest-0.0.2.1 sources via THC_DIGEST_SOURCE")
  originals <- files original
  forM_ originals $ \path -> do
    let destination = source </> makeRelative original path
    createDirectoryIfMissing True (takeDirectory destination)
    when (path /= destination) (copyFile path destination)
  -- Verify the retained tree too: repeated production must not conceal removed
  -- files behind a source-copy cache.
  retained <- files source
  unless (map (makeRelative original) originals == map (makeRelative source) retained)
    (fail "original digest source inventory changed; use a fresh fixture output directory")
  suppliedErf <- maybe (output </> "erf-2.0.0.0") id <$> lookupEnv "THC_ERF_SOURCE"
  originalErf <- canonicalizePath suppliedErf
  let erfSource = output </> "sources/erf-2.0.0.0"
      erfUnit = "erf-2.0.0.0-inplace"
  originalErfFiles <- files originalErf
  unless (originalErf </> "erf.cabal" `elem` originalErfFiles)
    (fail "package-native-originals requires unchanged erf-2.0.0.0 sources via THC_ERF_SOURCE")
  forM_ originalErfFiles $ \path -> do
    let destination = erfSource </> makeRelative originalErf path
    createDirectoryIfMissing True (takeDirectory destination)
    when (path /= destination) (copyFile path destination)
  retainedErf <- files erfSource
  unless (map (makeRelative originalErf) originalErfFiles == map (makeRelative erfSource) retainedErf)
    (fail "original erf source inventory changed; use a fresh fixture output directory")
  suppliedPrimitive <- maybe (output </> "primitive-0.9.1.0") id <$> lookupEnv "THC_PRIMITIVE_SOURCE"
  originalPrimitive <- canonicalizePath suppliedPrimitive
  let primitiveSource = output </> "sources/primitive-0.9.1.0"
      primitiveUnit = "primitive-0.9.1.0-inplace"
  originalPrimitiveFiles <- files originalPrimitive
  unless (originalPrimitive </> "primitive.cabal" `elem` originalPrimitiveFiles)
    (fail "package-native-originals requires unchanged primitive-0.9.1.0 sources via THC_PRIMITIVE_SOURCE")
  forM_ originalPrimitiveFiles $ \path -> do
    let destination = primitiveSource </> makeRelative originalPrimitive path
    createDirectoryIfMissing True (takeDirectory destination)
    when (path /= destination) (copyFile path destination)
  retainedPrimitive <- files primitiveSource
  unless (map (makeRelative originalPrimitive) originalPrimitiveFiles == map (makeRelative primitiveSource) retainedPrimitive)
    (fail "original primitive source inventory changed; use a fresh fixture output directory")
  built <- execute "driver-build" [] cabal ["build","--offline","-j2","exe:thc","lib:thc","exe:thc-interface"]
  driver <- locate execute cabal "exe:thc"
  helper <- locate execute cabal "exe:thc-interface"
  driverHash <- hashFile driver
  libdir <- line . commandStdout <$> execute "ghc-libdir" [] ghc ["--print-libdir"]
  registry <- either fail pure . eitherDecodeStrict' . commandStdout =<< execute "plugin-unit" [] python
    [root </> "bin/plugin.py","--root",root,"--ghc-pkg",ghcPkg,"--registry-only"]
  plugin <- field registry "unitId"
  pluginDb <- field registry "packageDb"
  sourceFiles <- files source
  sourceHashes <- hashes root (map (makeRelative root) (sourceFiles ++ retainedErf ++ retainedPrimitive))
  let key = take 16 driverHash
      native = output </> ("native-" ++ key)
      capture = output </> ("capture-" ++ key)
      pieces = output </> ("pieces-" ++ key)
      wrapper = output </> ("ghc-proxy-" ++ key) <.> "sh"
      project = output </> "digest.project"
      unit = "digest-0.0.2.1-inplace"
  writeFile project $ unlines ["packages: " ++ unwords (map show [source,erfSource,primitiveSource]),"jobs: 1","tests: False","benchmarks: False"]
  writeFile wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
  permissions <- getPermissions wrapper
  setPermissions wrapper permissions {executable=True}
  let environment = [("THC_PROXY_DRIVER",driver),("THC_PROXY_ROOT",root),("THC_PROXY_GHC",ghc),
        ("THC_PROXY_GLOBAL_UNITS",unlines [unit,erfUnit,primitiveUnit]),("THC_PROXY_CAPTURE",capture),("THC_PROXY_PLUGIN_DB",pluginDb),
        ("THC_PROXY_PLUGIN_UNIT",plugin),("THC_PROXY_INTERFACE_HELPER",helper),
        ("THC_PROXY_INTERFACE_LIBDIR",libdir),("THC_PROXY_NATIVE_PIECES",pieces)]
  acquired <- execute "digest-acquisition" environment cabal
    ["build","--offline","--project-file=" ++ project,"--builddir=" ++ native,
     "--with-compiler=" ++ wrapper,"lib:digest","lib:erf","lib:primitive"]
  plan <- readJson (native </> "cache/plan.json")
  planned <- field plan "install-plan" :: IO [Value]
  names <- mapM (\value -> field value "id") planned
  unless (all (`elem` (names :: [String])) [unit,erfUnit,primitiveUnit]) (fail "original package Cabal unit differs")
  let linkedDirectory = output </> "linked" </> unit
      erfLinkedDirectory = output </> "linked" </> erfUnit
      primitiveLinkedDirectory = output </> "linked" </> primitiveUnit
  modules <- stage (capture </> unit </> "core") linkedDirectory
  unless (sort (map fst modules) == ["Data.Digest.Adler32.cbd","Data.Digest.CRC32.cbd","Data.Digest.CRC32C.cbd"])
    (fail "original digest retained module inventory differs")
  linked <- finishPackageNative ghcPkg pieces (capture </> unit) unit Nothing modules
  erfModules <- stage (capture </> erfUnit </> "core") erfLinkedDirectory
  unless (map fst erfModules == ["Data.Number.Erf.cbd"]) (fail "original erf retained module inventory differs")
  erfLinked <- finishPackageNative ghcPkg pieces (capture </> erfUnit) erfUnit Nothing erfModules
  primitiveModules <- stage (capture </> primitiveUnit </> "core") primitiveLinkedDirectory
  unless (length primitiveModules == 14) (fail "original primitive retained module inventory differs")
  primitiveLinked <- finishPackageNative ghcPkg pieces (capture </> primitiveUnit) primitiveUnit Nothing primitiveModules
  compiled <- execute "digest-native-build" [] ghc
    ["-O1","-package-db",native </> "packagedb/ghc-9.14.1","-package-id",unit,
     "t/fixtures/compiler/OriginalDigestNative.hs","-outputdir",output </> "oracle-objects",
     "-o",output </> "digest-oracle"]
  oracle <- execute "digest-native-run" [] (output </> "digest-oracle") []
  unless (length (BSC.lines (commandStdout oracle)) == 270) (fail "original digest native row inventory differs")
  BS.writeFile (output </> "digest-native.tsv") (commandStdout oracle)
  erfCompiled <- execute "erf-native-build" [] ghc
    ["-O1","-package-db",native </> "packagedb/ghc-9.14.1","-package-id",erfUnit,
     "t/fixtures/compiler/OriginalErfNative.hs","-outputdir",output </> "erf-oracle-objects",
     "-o",output </> "erf-oracle"]
  erfOracle <- execute "erf-native-run" [] (output </> "erf-oracle") []
  unless (length (BSC.lines (commandStdout erfOracle)) == 88) (fail "original erf native row inventory differs")
  BS.writeFile (output </> "erf-native.tsv") (commandStdout erfOracle)
  primitiveCompiled <- execute "primitive-native-build" [] ghc
    ["-O1","-package-db",native </> "packagedb/ghc-9.14.1","-package-id",primitiveUnit,
     "t/fixtures/compiler/OriginalPrimitiveNative.hs","-outputdir",output </> "primitive-oracle-objects",
     "-o",output </> "primitive-oracle"]
  primitiveOracle <- execute "primitive-native-run" [] (output </> "primitive-oracle") []
  unless (length (BSC.lines (commandStdout primitiveOracle)) == 720) (fail "original primitive native row inventory differs")
  BS.writeFile (output </> "primitive-native.tsv") (commandStdout primitiveOracle)
  let primitiveEntryOutput = output </> "primitive-entry"
  createDirectoryIfMissing True primitiveEntryOutput
  primitiveEntryCompiled <- execute "primitive-entry-export" [] ghc
    ["-O1","-c","-fforce-recomp","-this-unit-id","original-primitive-entry",
     "-package-db",native </> "packagedb/ghc-9.14.1","-package-id",primitiveUnit,
     "-package-db",pluginDb,"-plugin-package-id",plugin,"-fplugin=THC.Plugin","-fplugin-trustworthy",
     "-fplugin-opt=THC.Plugin:" ++ primitiveEntryOutput,"-fplugin-opt=THC.Plugin:post-tidy",
     "-fplugin-opt=THC.Plugin:unit-qualified","-fplugin-opt=THC.Plugin:foreign-import-provenance",
     "-fwrite-if-simplified-core","-dcore-lint","t/fixtures/compiler/OriginalPrimitiveEntry.hs",
     "-outputdir",primitiveEntryOutput]
  primitiveAudited <- execute "primitive-entry-audit" [] "python3"
    (["bin/audit-core.py","--output",output </> "primitive-audit.json"] ++
     concatMap (\name -> ["--entry","original-primitive-entry:OriginalPrimitiveEntry." ++ name])
       ["signed16","unsigned16","signed64"] ++
     [primitiveEntryOutput </> "units/u-original-primitive-entry/OriginalPrimitiveEntry.cbd"] ++
     [primitiveLinkedDirectory </> name | (name,_) <- primitiveLinked])
  let entryOutput = output </> "erf-entry"
  createDirectoryIfMissing True entryOutput
  entryCompiled <- execute "erf-entry-export" [] ghc
    ["-O1","-c","-fforce-recomp","-this-unit-id","original-erf-entry",
     "-package-db",native </> "packagedb/ghc-9.14.1","-package-id",erfUnit,
     "-package-db",pluginDb,"-plugin-package-id",plugin,"-fplugin=THC.Plugin","-fplugin-trustworthy",
     "-fplugin-opt=THC.Plugin:" ++ entryOutput,"-fplugin-opt=THC.Plugin:post-tidy",
     "-fplugin-opt=THC.Plugin:unit-qualified","-fplugin-opt=THC.Plugin:foreign-import-provenance",
     "-fwrite-if-simplified-core","-dcore-lint","t/fixtures/compiler/OriginalErfEntry.hs",
     "-outputdir",entryOutput]
  audited <- execute "erf-entry-audit" [] "python3"
    (["bin/audit-core.py","--output",output </> "erf-audit.json"] ++
     concatMap (\name -> ["--entry","original-erf-entry:OriginalErfEntry." ++ name])
       ["erfDouble","erfcDouble","erfFloat","erfcFloat"] ++
     [erfLinkedDirectory </> "Data.Number.Erf.cbd",entryOutput </> "units/u-original-erf-entry/OriginalErfEntry.cbd"])
  inputs <- hashes root ["t/fixtures/compiler/OriginalDigestNative.hs","t/fixtures/compiler/OriginalPrimitiveNative.hs",
    "t/fixtures/compiler/OriginalPrimitiveEntry.hs",
    "t/fixtures/compiler/OriginalErfNative.hs","t/fixtures/compiler/OriginalErfEntry.hs",
    "t/haskell-fixtures/PackageNativeOriginalsFixtures.hs","bin/plugin.py","src/driver/THC/Driver/PackageNative.hs",
    "src/driver/THC/Driver/NativeArgumentBridge.hs",
    "src/driver/THC/Driver/NativeLibrarySources.hs","src/driver/THC/Driver/GhcProxy.hs",
    "bin/audit-core.py","bin/core_package_manifest.py","bin/core-capabilities.json"]
  artifacts <- hashes root ([relative </> "digest-native.tsv",relative </> "erf-native.tsv",relative </> "primitive-native.tsv",
    relative </> "erf-entry/units/u-original-erf-entry/OriginalErfEntry.cbd",relative </> "erf-audit.json",
    relative </> "primitive-entry/units/u-original-primitive-entry/OriginalPrimitiveEntry.cbd",relative </> "primitive-audit.json"] ++
    [relative </> "linked" </> unit </> name | (name,_) <- linked] ++
    [relative </> "linked" </> erfUnit </> name | (name,_) <- erfLinked] ++
    [relative </> "linked" </> primitiveUnit </> name | (name,_) <- primitiveLinked])
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1::Int),"scope" .= ("original-package-foreign-adapters"::String),
     "unit" .= unit,"nativeRows" .= (270::Int),"erfNativeRows" .= (88::Int),"primitiveNativeRows" .= (720::Int),
     "driverSha256" .= driverHash,"inputHashes" .= inputs,
     "sourceHashes" .= sourceHashes,"artifactHashes" .= artifacts,
     "commands" .= map commandRecord [built,acquired,compiled,oracle,erfCompiled,erfOracle,
       primitiveCompiled,primitiveOracle,primitiveEntryCompiled,primitiveAudited,entryCompiled,audited]]
  putStrLn "package-native-originals: original digest, erf and primitive acquisition; 270 + 88 + 720 native observations"
  where
    line bytes = case BSC.lines bytes of [value] -> BSC.unpack value; _ -> error "expected exactly one tool result"
    locate execute cabal target = line . commandStdout <$> execute
      ("locate-" ++ drop 4 target) [] cabal ["list-bin","--offline",target]
    stage captured destination = do
      createDirectoryIfMissing True destination
      paths <- filter ((== ".cbd") . takeExtension) <$> files captured
      forM paths $ \path -> do
        let name = takeFileName path
            staged = destination </> name
        copyFile path staged
        pure (name,staged)
    files directory = do
      names <- sort <$> listDirectory directory
      concat <$> forM names (\name -> do
        let path = directory </> name
        nested <- doesDirectoryExist path
        if nested then files path else pure [path])
