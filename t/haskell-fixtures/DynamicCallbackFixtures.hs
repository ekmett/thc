-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (162 dynamic-callback and static-export)
-- Purpose: Check wrapper/dynamic callbacks and static exports preserve pointer
--   lifetime, native retention/release and guest context ownership.
-- Consumes: DynamicCallback{,Native}.hs/dynamic-callback.c, run-static-exports sources,
--   GHC/native toolchain, interface helper/plugin and THC_PACKAGE_NATIVE_SUPPORT.
-- Produces/consumed result: DynamicCallback/NativeExport CBDs, package-native products,
--   oracle texts, two audits and manifest under build/dynamic-callback.
-- Cost and overlap: Real THC callback/ABI boundaries justify integration. Share tools
--   and support packages; keep fixture-free callback rejection tests independent.
-- Build status: Review candidate. Runtime-support manifest and referenced artifacts
--   need explicit graph edges; build-directory selection is not a dependency rule.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 162.
{-# LANGUAGE OverloadedStrings #-}

-- | Original wrapper/dynamic imports through the existing native package producer.
module DynamicCallbackFixtures (prepareDynamicCallbacks) where

import Control.Monad (forM_, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv)
import System.FilePath
import THC.Driver.PackageNative

prepareDynamicCallbacks :: FilePath -> IO ()
prepareDynamicCallbacks root = do
  let relative = "build/dynamic-callback"
      output = root </> relative
      objects = output </> "objects"
      native = output </> "native-oracle"
      pieces = output </> "pieces"
      source = "t/fixtures/compiler/DynamicCallback.hs"
      nativeSource = "t/fixtures/compiler/DynamicCallbackNative.hs"
      cSource = "t/fixtures/compiler/dynamic-callback.c"
      sources = [source, nativeSource, cSource]
      execute = runLogged 180 root (relative </> "logs")
  forM_ [objects,native,output </> "core"] (createDirectoryIfMissing True)
  support <- lookupEnv "THC_PACKAGE_NATIVE_SUPPORT" >>= maybe
    (fail "Set THC_PACKAGE_NATIVE_SUPPORT to a genuine GHC/exception-runtime manifest") canonicalizePath
  copyFile support (output </> "runtime-support.json")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  built <- execute "helper-build" [] cabal ["build","--offline","-j2","lib:thc","exe:thc-interface"]
  located <- execute "helper-location" [] cabal ["list-bin","--offline","exe:thc-interface"]
  let helper = line (commandStdout located)
  pluginRecord <- execute "plugin-registry" [] "python3" ["bin/plugin.py","--root",root,"--registry-only"]
  BS.writeFile (output </> "plugin.json") (commandStdout pluginRecord)
  plugin <- readJson (output </> "plugin.json")
  pluginDb <- field plugin "packageDb"
  pluginUnit <- field plugin "unitId"
  compiled <- execute "original-export" [] ghc
    ["-c","-O2","-dynamic","-fforce-recomp","-dcore-lint","-this-unit-id","callback-fixture",
     "-fwrite-if-simplified-core","-package-db",pluginDb,"-plugin-package-id",pluginUnit,"-fplugin=THC.Plugin",
     "-fplugin-opt=THC.Plugin:" ++ output </> "core","-fplugin-opt=THC.Plugin:post-tidy",
     "-fplugin-opt=THC.Plugin:unit-qualified","-fplugin-opt=THC.Plugin:foreign-import-provenance",
     "-odir",objects,"-hidir",objects,"-stubdir",objects,source]
  libdirResult <- execute "libdir" [] ghc ["--print-libdir"]
  let libdir = line (commandStdout libdirResult)
  hydrated <- execute "original-interface" [] helper ["--libdir",libdir,"--unit","callback-fixture",
    "--module","DynamicCallback","--interface",objects </> "DynamicCallback.hi","--way","dynamic",
    "--home-interfaces",objects]
  BS.writeFile (output </> "interface-response.cbd") (commandStdout hydrated)
  let captured = output </> "core/units/u-callback-fixture/DynamicCallback.cbd"
  BS.writeFile captured (commandStdout hydrated)
  let cArguments = ["-c","-O2",cSource,"-o",objects </> "callback.o"]
  cCompiled <- execute "native-component" [] ghc cArguments
  captureNativeObject pieces ghc cArguments
  capturePackageNative helper libdir ghc ["-dynamic","-odir",objects] "callback-fixture" output
  let staged = output </> "DynamicCallback.cbd"
  copyFile captured staged
  _ <- finishPackageNative ghcPkg pieces output "callback-fixture" (Just [objects </> "callback.o"]) [("DynamicCallback.cbd",staged)]
  oracleBuilt <- execute "native-build" [] ghc
    ["--make","-O2","-fforce-recomp","-dcore-lint","-i","-it/fixtures/compiler",
     "-odir",native,"-hidir",native,"-stubdir",native,nativeSource,cSource,"-o",native </> "oracle"]
  oracle <- execute "native-oracle" [] (native </> "oracle") []
  unless (commandStdout oracle == "(12,13,2,24)\n(16,1,4)\n" && BS.null (commandStderr oracle))
    (fail "Original callback oracle differs")
  BS.writeFile (output </> "oracle.txt") (commandStdout oracle)
  audited <- execute "strict-audit" [] "python3"
    (["bin/audit-core.py","--package-manifest",output </> "runtime-support.json",
      "--output",output </> "audit.json"] ++ concatMap (\name -> ["--entry","callback-fixture:DynamicCallback." ++ name])
      ["run","makePointer","callPointer","unsafePointer","releasePointer","echoPointer"] ++ [output </> "DynamicCallback.cbd"])
  staticCommands <- prepareStaticExport root output ghc helper libdir pluginDb pluginUnit
  inputHashes <- hashes root (sources ++ ["t/fixtures/run-static-exports/NativeExport.hs", "t/fixtures/run-static-exports/Main.hs",
    "t/fixtures/run-static-exports/cbits/callbacks.c", "t/fixtures/run-static-exports/cbits/callbacks.h",
    "t/haskell-fixtures/DynamicCallbackFixtures.hs", "src/compiler/THC/ForeignExportProvenance.hs",
    "src/compiler/THC/ForeignImportProvenance.hs","src/compiler/THC/Plugin.hs",
    "src/driver/THC/Driver/PackageNative.hs","bin/audit-core.py","bin/core_package_manifest.py"])
  artifactHashes <- hashes root [relative </> name | name <-
    ["DynamicCallback.cbd","runtime-support.json","interface-response.cbd","oracle.txt","audit.json",
      "NativeExport.cbd", "static-oracle.txt", "static-audit.json"]]
  writeJson (output </> "manifest.json") $ object ["schema" .= (1 :: Int),"inputHashes" .= inputHashes,
    "artifactHashes" .= artifactHashes,"commands" .= map commandRecord
      ([built,located,pluginRecord,compiled,libdirResult,hydrated,cCompiled,oracleBuilt,oracle,audited] ++ staticCommands)]
  putStrLn "dynamic-callback: original scalar/address callbacks, retained native pointer and native GHC oracle"
  where
    line bytes = case BSC.lines bytes of [value] -> BSC.unpack value; _ -> error "Expected one selected tool path"

-- Same original mixed-declaration module as the ordinary driver replay control.
-- Its C constructor retains the export; C also releases the guest StablePtr.
prepareStaticExport :: FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> FilePath -> String -> IO [CommandResult]
prepareStaticExport root output ghc helper libdir pluginDb pluginUnit = do
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  let source = "t/fixtures/run-static-exports"
      capture = output </> "static"
      objects = capture </> "objects"
      native = capture </> "oracle"
      unit = "static-export-fixture"
      execute = runLogged 180 root ("build/dynamic-callback/logs")
      cArguments = ["-c", "-O2", "-I" ++ root </> source </> "cbits", source </> "cbits/callbacks.c", "-o", objects </> "callbacks.o"]
  forM_ [objects, native, capture </> "core"] (createDirectoryIfMissing True)
  compiled <- execute "static-original-export" [] ghc
    ["-c", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-this-unit-id", unit, "-fwrite-if-simplified-core",
     "-package-db", pluginDb, "-plugin-package-id", pluginUnit, "-fplugin=THC.Plugin",
     "-fplugin-opt=THC.Plugin:" ++ capture </> "core", "-fplugin-opt=THC.Plugin:post-tidy", "-fplugin-opt=THC.Plugin:unit-qualified",
     "-fplugin-opt=THC.Plugin:foreign-import-provenance", "-fplugin-opt=THC.Plugin:foreign-export-associations",
     "-fplugin-opt=THC.Plugin:foreign-export-registration", "-I" ++ root </> source </> "cbits",
     "-odir", objects, "-hidir", objects, "-stubdir", objects, source </> "NativeExport.hs"]
  cCompiled <- execute "static-native-component" [] ghc cArguments
  captureNativeObject (capture </> "pieces") ghc cArguments
  capturePackageNative helper libdir ghc ["-dynamic", "-I" ++ root </> source </> "cbits", "-odir", objects] unit capture
  let staged = output </> "NativeExport.cbd"
  copyFile (capture </> "core/units/u-static-export-fixture/NativeExport.cbd") staged
  _ <- finishPackageNative ghcPkg (capture </> "pieces") capture unit (Just [objects </> "callbacks.o"]) [("NativeExport.cbd",staged)]
  oracleBuilt <- execute "static-native-build" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-i", "-i" ++ source, "-I" ++ root </> source </> "cbits",
     "-odir", native, "-hidir", native, "-stubdir", native, source </> "Main.hs", source </> "cbits/callbacks.c", "-o", native </> "main"]
  oracle <- execute "static-native-oracle" [] (native </> "main") []
  unless (commandStdout oracle == "43\n" && BS.null (commandStderr oracle)) (fail "Original static callback oracle differs")
  BS.writeFile (output </> "static-oracle.txt") (commandStdout oracle)
  audited <- execute "static-strict-audit" [] "python3"
    ["bin/audit-core.py", "--package-manifest", output </> "runtime-support.json", "--entry", unit ++ ":NativeExport.probe",
     "--output", output </> "static-audit.json", output </> "NativeExport.cbd"]
  pure [compiled, cCompiled, oracleBuilt, oracle, audited]
