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
module PackageNativeArchiveFixtures (preparePackageNativeArchives, preparePackageNativeGcCarriers) where

import Control.Monad (forM, unless)
import Data.Aeson (eitherDecodeStrict', Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (sort, isSuffixOf)
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv, unsetEnv)
import System.FilePath
import Text.Read (readMaybe)
import THC.Driver.GhcProxy (ghcProxyCommand)
import THC.Driver.PackageNative (finishPackageNative, archiveNativeModule, nativeSignatures)
import THC.Compact.Module (readModuleValue, finalizeModuleMetadata)

-- A focused genuine compiler/RTS control, independent of the broad Cabal
-- archive acquisition. Native GHC owns every closure used by the oracle.
preparePackageNativeGcCarriers :: FilePath -> IO ()
preparePackageNativeGcCarriers root = do
  let relative = "build/package-native-gc-carriers"
      output = root </> relative
      source = "t/fixtures/compiler/PackageNativeGcCarriers.hs"
      objects = output </> "ghc"
      core = output </> "PackageNativeGcCarriers.cbd"
      execute = runLogged 600 root (relative </> "logs")
      line bytes = case BSC.lines bytes of [value] -> pure (BSC.unpack value); _ -> fail "expected one tool result"
  createDirectoryIfMissing True output
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  exported <- execute "export" [("THC_CORE_OUT",output </> "source-core"),("THC_GHC_OUT",objects)]
    "bin/export-core.sh" ["-package","ghc-internal","-fwrite-if-simplified-core",
      "-fplugin-opt=THC.Plugin:foreign-import-provenance",source]
  _ <- execute "interface-build" [] cabal ["build","exe:thc-interface","--offline"]
  helper <- execute "interface-path" [] cabal ["list-bin","exe:thc-interface","--offline"] >>= line . commandStdout
  libdir <- execute "libdir" [] ghc ["--print-libdir"] >>= line . commandStdout
  hydrated <- execute "hydrate" [] helper ["--libdir",libdir,"--unit","main",
    "--module","PackageNativeGcCarriers","--way","dynamic","--home-interfaces",objects,
    "--interface",objects </> "PackageNativeGcCarriers.hi"]
  BS.writeFile core (commandStdout hydrated)
  original <- either fail pure (readModuleValue (commandStdout hydrated))
  proof <- field original "staticForeignImports" :: IO Value
  status <- field proof "status" :: IO String
  unless (status == "verified") (fail "genuine GC-carrier producer did not verify its stock import products")
  archived <- either fail pure (archiveNativeModule "main" original)
  signatures <- either fail pure (nativeSignatures "main" [archived])
  unless (signatures == [("getpid","ccall","unsafe",[],"Int32Rep")])
    (fail "GC carriers leaked into native adapters or ordinary scalar import was lost")
  marker <- field archived "packageNativeArchive" :: IO Value
  excluded <- field marker "unsupportedImports" :: IO [Value]
  symbols <- sort <$> mapM (\entry -> field entry "symbol" :: IO String) excluded
  unless (symbols == sort ["rts_getThreadId","eq_thread","cmp_thread","rts_enableThreadAllocationLimit",
    "rts_disableThreadAllocationLimit","rts_setMainThread","reportStackOverflow"])
    (fail "genuine GC-carrier archive inventory differs")
  finalizeModuleMetadata core archived
  validated <- execute "archive-validate" [("PYTHONPATH",root </> "bin")] "python3"
    ["-c","import pathlib,sys,core_package_manifest as c; c.package_native_archive(c.inspect_cbd(pathlib.Path(sys.argv[1]).read_bytes()))",core]
  createDirectoryIfMissing True (output </> "native")
  compiled <- execute "native-build" [] ghc ["--make","-O2","-fforce-recomp","-package","ghc-internal",
    "-odir",output </> "native","-hidir",output </> "native","-main-is","PackageNativeGcCarriers.main",
    source,"-o",output </> "oracle"]
  oracle <- execute "native-oracle" [] (output </> "oracle") []
  unless (BSC.lines (commandStdout oracle) == replicate 6 "True") (fail "native GC-carrier relational oracle differs")
  BS.writeFile (output </> "oracle.txt") (commandStdout oracle)
  inputs <- hashes root [source,"t/haskell-fixtures/PackageNativeArchiveFixtures.hs",
    "src/compiler/THC/ForeignImportProvenance.hs","src/driver/THC/Driver/PackageNative.hs"]
  artifacts <- hashes root [relative </> "PackageNativeGcCarriers.cbd",relative </> "oracle.txt"]
  originals <- prepareOriginalGcCarriers root ghc helper libdir
  primitive <- preparePrimitiveCarriers root helper libdir
  writeJson (output </> "manifest.json") (object ["schema" .= (1::Int),"inputHashes" .= inputs,
    "artifactHashes" .= artifacts,"nativeRows" .= (6::Int),"gcImports" .= (7::Int),
    "originalModules" .= originals,
    "primitiveModule" .= primitive,
    "commands" .= map commandRecord [exported,hydrated,validated,compiled,oracle]])
  putStrLn "package-native-gc-carriers: exact GC imports archived; scalar adapter retained; six native relational observations"

-- Pure scalar/tuple and nested zero-width prim results must survive the stock
-- probe without being mistaken for a C State ABI. No stack pointer is executed.
preparePrimitiveCarriers :: FilePath -> FilePath -> FilePath -> IO Value
preparePrimitiveCarriers root helper libdir = do
  let relative = "build/package-native-gc-carriers/primitive"
      output = root </> relative
      source = "t/fixtures/compiler/PackageNativePrimCarriers.hs"
      objects = output </> "objects"
      core = output </> "PackageNativePrimCarriers.cbd"
      execute = runLogged 180 root (relative </> "logs")
  createDirectoryIfMissing True output
  exported <- execute "export" [("THC_CORE_OUT",output </> "source-core"),("THC_GHC_OUT",objects)]
    "bin/export-core.sh" ["-package","ghc-internal","-fwrite-if-simplified-core",
      "-fplugin-opt=THC.Plugin:foreign-import-provenance",source]
  hydrated <- execute "hydrate" [] helper ["--libdir",libdir,"--unit","main",
    "--module","PackageNativePrimCarriers","--way","dynamic","--home-interfaces",objects,
    "--interface",objects </> "PackageNativePrimCarriers.hi"]
  BS.writeFile core (commandStdout hydrated)
  original <- either fail pure (readModuleValue (commandStdout hydrated))
  proof <- field original "staticForeignImports" :: IO Value
  status <- field proof "status" :: IO String
  profile <- field proof "profile" :: IO String
  unless (status == "verified" && profile == "ghc-9.14.1-thc-stock-static-foreign-imports-v2")
    (fail "stock primitive declarations did not retain their v2 provenance")
  archived <- either fail pure (archiveNativeModule "main" original)
  signatures <- either fail pure (nativeSignatures "main" [archived])
  unless (null signatures) (fail "primitive declarations leaked into native adapters")
  marker <- field archived "packageNativeArchive" :: IO Value
  excluded <- field marker "unsupportedImports" :: IO [Value]
  unless (length excluded == 4) (fail "primitive archive inventory differs")
  results <- sort <$> mapM (\entry -> field entry "result" :: IO [String]) excluded
  unless (results == sort [["WordRep"],["AddrRep","AddrRep"],
      ["BoxedRep (Just Unlifted)","WordRep","IntRep"],["void","void"]])
    (fail "primitive scalar, tuple or nested zero-width result was flattened incorrectly")
  finalizeModuleMetadata core archived
  validated <- execute "validate" [("PYTHONPATH",root </> "bin")] "python3"
    ["-c","import pathlib,sys,core_package_manifest as c; c.package_native_archive(c.inspect_cbd(pathlib.Path(sys.argv[1]).read_bytes()))",core]
  let unknownSource = "t/fixtures/compiler/PackageNativeUnknownPrim.hs"
      unknownCore = output </> "PackageNativeUnknownPrim.cbd"
  unknownExport <- execute "unknown-export" [("THC_CORE_OUT",output </> "unknown-source-core"),("THC_GHC_OUT",objects)]
    "bin/export-core.sh" ["-package","ghc-internal","-fwrite-if-simplified-core",
      "-fplugin-opt=THC.Plugin:foreign-import-provenance",unknownSource]
  unknownHydrate <- execute "unknown-hydrate" [] helper ["--libdir",libdir,"--unit","main",
    "--module","PackageNativeUnknownPrim","--way","dynamic","--home-interfaces",objects,
    "--interface",objects </> "PackageNativeUnknownPrim.hi"]
  unknown <- either fail pure (readModuleValue (commandStdout unknownHydrate))
  unknownProof <- field unknown "staticForeignImports" :: IO Value
  unknownStatus <- field unknownProof "status" :: IO String
  unless (unknownStatus == "unclassified") (fail "unknown primitive nominal carrier gained verified provenance")
  unknownArchive <- either fail pure (archiveNativeModule "main" unknown)
  unknownSignatures <- either fail pure (nativeSignatures "main" [unknownArchive])
  unless (null unknownSignatures) (fail "unknown primitive carrier gained a native adapter")
  BS.writeFile unknownCore (commandStdout unknownHydrate)
  finalizeModuleMetadata unknownCore unknownArchive
  unknownValidated <- execute "unknown-validate" [("PYTHONPATH",root </> "bin")] "python3"
    ["-c","import pathlib,sys,core_package_manifest as c; m=c.inspect_cbd(pathlib.Path(sys.argv[1]).read_bytes()); a=c.package_native_archive(m); assert a['unclassifiedReason']=='non-static-c-import-declaration'; assert all(c.native_archive_blocks(m,b,a) for b in m['bindings'])",unknownCore]
  inputs <- hashes root [source,unknownSource,"src/compiler/THC/ForeignImportProvenance.hs","src/compiler/THC/Plugin.hs",
    "src/driver/THC/Driver/PackageNative.hs"]
  artifacts <- hashes root [core,unknownCore,objects </> "PackageNativePrimCarriers.hi"]
  pure (object ["inputHashes" .= inputs,"artifactHashes" .= artifacts,
    "nativeSignatures" .= signatures,"commands" .= map commandRecord
      [exported,hydrated,validated,unknownExport,unknownHydrate,unknownValidated]])

-- The normal Stack proof uses pinned original source and the selected compiler's
-- installed interfaces. Explicit source/home inputs retain the broader original
-- GC qualification. Copies isolate GHC's one-shot -hidir lookup and writes;
-- dynamic interface contents are unchanged, only their private lookup suffix is
-- selected to match the existing dynamic external-plugin acquisition.
prepareOriginalGcCarriers :: FilePath -> FilePath -> FilePath -> FilePath -> IO [Value]
prepareOriginalGcCarriers root ghc helper libdir = do
  sourceInput <- lookupEnv "THC_GC_CARRIER_GHC_SOURCE"
  homeInput <- lookupEnv "THC_GC_CARRIER_HOME_INTERFACES"
  case (sourceInput,homeInput) of
    (Nothing,Nothing) -> do
      ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
      selected <- runLogged 180 root "build/package-native-gc-carriers/original-v2/logs"
        "installed-interfaces" [] ghcPkg ["field","ghc-internal","import-dirs","--simple-output"]
      home <- case BSC.lines (commandStdout selected) of
        [value] | not (BS.null value) -> pure (BSC.unpack value)
        _ -> fail "expected one selected ghc-internal interface directory"
      prepareOriginal [stack] (root </> "nih/pinned/ghc-9.14.1") home
    (Just suppliedSource,Just suppliedHome) -> prepareOriginal
      [("GHC.Internal.Conc.Sync",["rts_getThreadId","eq_thread","cmp_thread",
          "rts_enableThreadAllocationLimit","rts_disableThreadAllocationLimit","reportStackOverflow"]),
       ("GHC.Internal.TopHandler",["rts_setMainThread"]),stack] suppliedSource suppliedHome
    _ -> fail "Set both THC_GC_CARRIER_GHC_SOURCE and THC_GC_CARRIER_HOME_INTERFACES, or neither"
  where
    stack = ("GHC.Internal.Stack.Decode",["getUnderflowFrameNextChunkzh","getWordzh","isArgGenBigRetFunTypezh",
      "getLargeBitmapzh","getBCOLargeBitmapzh","getRetFunLargeBitmapzh","getSmallBitmapzh",
      "getRetFunSmallBitmapzh","getInfoTableAddrszh","getStackInfoTableAddrzh","getStackClosurezh",
      "getStackFieldszh","advanceStackFrameLocationzh"])
    prepareOriginal modules suppliedSource suppliedHome = do
      sourceRoot <- canonicalizePath suppliedSource
      home <- canonicalizePath suppliedHome
      let relative = "build/package-native-gc-carriers/original-v2"
          output = root </> relative
          objects = output </> "objects"
          execute = runLogged 180 root (relative </> "logs")
          line result = case BSC.lines (commandStdout result) of
            [value] -> pure (BSC.unpack value)
            _ -> fail "expected one original GC tool result"
          copyInterfaces directory = do
            names <- listDirectory (home </> directory)
            concat <$> forM names (\name -> do
              let path = directory </> name
              isDirectory <- doesDirectoryExist (home </> path)
              if isDirectory then copyInterfaces path else
                forM [suffix | suffix <- [".dyn_hi",".dyn_hi-boot"], suffix `isSuffixOf` name] $ \suffix -> do
                  let target = objects </> directory </> take (length name - length suffix) name
                        ++ if suffix == ".dyn_hi" then ".hi" else ".hi-boot"
                  createDirectoryIfMissing True (takeDirectory target)
                  copyFile (home </> path) target
                  pure target)
      createDirectoryIfMissing True output
      homeHashes <- hashes root =<< copyInterfaces "GHC"
      pluginDb <- execute "plugin-db" [] "python3" ["bin/plugin.py","--field","packageDb"] >>= line
      plugin <- execute "plugin" [] "python3" ["bin/plugin.py","--external-plugin",output </> "source-core",
        "-fplugin-opt=THC.Plugin:foreign-import-provenance","-fplugin-opt=THC.Plugin:post-tidy",
        "-fplugin-opt=THC.Plugin:unit-qualified"] >>= line
      forM modules $ \(name,expectedGc) -> do
        let modulePath = map (\character -> if character == '.' then pathSeparator else character) name
            source = sourceRoot </> "libraries/ghc-internal/src" </> modulePath <.> "hs"
            core = output </> name <.> "cbd"
        compiled <- execute (name ++ "-compile") [] ghc $ ["-c","-dynamic","-O2","-fforce-recomp",
          "-fwrite-if-simplified-core","-dcore-lint","-this-unit-id","ghc-internal",
          "-this-package-name","ghc-internal","-hide-all-packages","-package","rts",
          "-i","-i" ++ objects,"-I" ++ (home </> "include"),"-package-db",pluginDb,plugin,
          "-odir",objects,"-hidir",objects] ++
          ["-XNoImplicitPrelude" | name == "GHC.Internal.Stack.Decode"] ++ [source]
        hydrated <- execute (name ++ "-hydrate") [] helper ["--libdir",libdir,"--unit","ghc-internal",
          "--module",name,"--way","dynamic","--home-interfaces",objects,
          "--interface",objects </> modulePath <.> "hi"]
        BS.writeFile core (commandStdout hydrated)
        original <- either fail pure (readModuleValue (commandStdout hydrated))
        actualUnit <- field original "unit" :: IO String
        actualModule <- field original "module" :: IO String
        unless (actualUnit == "ghc-internal" && actualModule == name)
          (fail "original GC module identity differs")
        proof <- field original "staticForeignImports" :: IO Value
        status <- field proof "status" :: IO String
        unless (status == "verified") (fail "original GC import inventory is not verified")
        archived <- either fail pure (archiveNativeModule "ghc-internal" original)
        marker <- field archived "packageNativeArchive" :: IO Value
        excluded <- field marker "unsupportedImports" :: IO [Value]
        symbols <- sort <$> mapM (\entry -> field entry "symbol" :: IO String) excluded
        unless (symbols == sort expectedGc) (fail "original GC archive exclusion inventory differs")
        signatures <- either fail pure (nativeSignatures "ghc-internal" [archived])
        unless (name /= "GHC.Internal.Stack.Decode" || null signatures)
          (fail "original Stack.Decode primitive imports leaked into native adapters")
        finalizeModuleMetadata core archived
        validated <- execute (name ++ "-validate") [("PYTHONPATH",root </> "bin")] "python3"
          ["-c","import pathlib,sys,core_package_manifest as c; c.package_native_archive(c.inspect_cbd(pathlib.Path(sys.argv[1]).read_bytes()))",core]
        inputs <- hashes root [source]
        artifacts <- hashes root [core,objects </> modulePath <.> "hi"]
        pure (object ["unit" .= ("ghc-internal"::String),"module" .= name,"inputHashes" .= inputs,
          "homeInterfaceHashes" .= homeHashes,
          "artifactHashes" .= artifacts,"nativeSignatures" .= signatures,
          "commands" .= map commandRecord [compiled,hydrated,validated]])

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
    products <- finishPackageNative ghcPkg pieces (capture </> unit) unit Nothing modules
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
