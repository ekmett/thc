-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module GetEntropyFixtures (prepareGetEntropy) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (sort)
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv, unsetEnv)
import System.FilePath
import THC.Driver.GhcProxy (ghcProxyCommand)
import THC.Driver.NativeLibrarySources (validateNativeEntropyIR)
import THC.Driver.PackageNative (finishPackageNative)

-- Use the unchanged package, actual Cabal configuration, production GHC proxy,
-- typed foreign-import capture and final linker. No test-only symbol resolver.
prepareGetEntropy :: FilePath -> IO ()
prepareGetEntropy root = do
  unsetEnv "GHC_ENVIRONMENT"
  let relative = "build/getentropy"
      output = root </> relative
      execute = runLogged 600 root (relative </> "logs")
      unit = "splitmix-0.1.3.2-inplace"
  createDirectoryIfMissing True output
  original <- maybe (fail "getentropy requires unchanged splitmix-0.1.3.2 via THC_SPLITMIX_SOURCE") canonicalizePath
    =<< lookupEnv "THC_SPLITMIX_SOURCE"
  originalFiles <- files original
  unless (original </> "splitmix.cabal" `elem` originalFiles)
    (fail "getentropy source lacks original splitmix.cabal")
  let source = output </> "sources/splitmix-0.1.3.2"
  forM_ originalFiles $ \path -> do
    let destination = source </> makeRelative original path
    createDirectoryIfMissing True (takeDirectory destination)
    when (path /= destination) (copyFile path destination)
  retained <- files source
  unless (map (makeRelative original) originalFiles == map (makeRelative source) retained)
    (fail "splitmix source inventory changed; use a fresh fixture output directory")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  clang <- maybe "clang" id <$> lookupEnv "THC_CLANG"
  built <- execute "driver-build" [] cabal ["build","--offline","-j2","exe:thc","lib:thc","exe:thc-interface"]
  driver <- locate execute cabal "exe:thc"
  helper <- locate execute cabal "exe:thc-interface"
  driverHash <- hashFile driver
  let pluginDb = root </> "dist-newstyle/packagedb/ghc-9.14.1"
  libdir <- line . commandStdout <$> execute "ghc-libdir" [] ghc ["--print-libdir"]
  plugin <- line . commandStdout <$> execute "plugin-unit" [] ghcPkg
    ["--package-db",pluginDb,"field","thc","id","--simple-output"]
  let key = take 16 driverHash
      native = output </> ("native-" ++ key)
      capture = output </> ("capture-" ++ key)
      pieces = output </> ("pieces-" ++ key)
      wrapper = output </> ("ghc-proxy-" ++ key) <.> "sh"
      project = output </> "splitmix.project"
  writeFile project $ unlines ["packages: " ++ show source,"jobs: 1","tests: False","benchmarks: False"]
  writeFile wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
  permissions <- getPermissions wrapper
  setPermissions wrapper permissions {executable=True}
  let environment = [("THC_PROXY_DRIVER",driver),("THC_PROXY_ROOT",root),("THC_PROXY_GHC",ghc),
        ("THC_PROXY_GLOBAL_UNITS",unit),("THC_PROXY_CAPTURE",capture),("THC_PROXY_PLUGIN_DB",pluginDb),
        ("THC_PROXY_PLUGIN_UNIT",plugin),("THC_PROXY_INTERFACE_HELPER",helper),
        ("THC_PROXY_INTERFACE_LIBDIR",libdir),("THC_PROXY_NATIVE_PIECES",pieces)]
  acquired <- execute "splitmix-acquisition" environment cabal
    ["build","--offline","--project-file=" ++ project,"--builddir=" ++ native,
     "--with-compiler=" ++ wrapper,"lib:splitmix"]
  plan <- readJson (native </> "cache/plan.json")
  planned <- field plan "install-plan" :: IO [Value]
  names <- mapM (\value -> field value "id") planned
  unless (unit `elem` (names :: [String])) (fail "original splitmix Cabal unit differs")
  paths <- filter ((== ".json") . takeExtension) <$> files (capture </> unit </> "core")
  modules <- forM paths $ \path -> (,) (takeFileName path) <$> BS.readFile path
  unless (sort (map fst modules) == ["System.Random.SplitMix.Init.json","System.Random.SplitMix.json","System.Random.SplitMix32.json"])
    (fail "splitmix retained module inventory differs")
  linked <- finishPackageNative pieces (capture </> unit) unit Nothing modules
  forM_ linked $ \(name,value) -> BS.writeFile (output </> name) value
  let entryOutput = output </> "entry"
      entryPath = entryOutput </> "units/u-original-splitmix-entry/OriginalSplitmixEntry.json"
  createDirectoryIfMissing True entryOutput
  entryBuilt <- execute "entry-export" [] ghc
    ["-O1","-c","-fforce-recomp","-this-unit-id","original-splitmix-entry",
     "-package-db",native </> "packagedb/ghc-9.14.1","-package-id",unit,
     "-package-db",pluginDb,"-plugin-package-id",plugin,"-fplugin=THC.Plugin","-fplugin-trustworthy",
     "-fplugin-opt=THC.Plugin:" ++ entryOutput,"-fplugin-opt=THC.Plugin:source-notes",
     "-fplugin-opt=THC.Plugin:unit-qualified","-fplugin-opt=THC.Plugin:post-tidy",
     "-fplugin-opt=THC.Plugin:foreign-import-provenance","-fwrite-if-simplified-core",
     "-dcore-lint","-outputdir",output </> "entry-objects","compiler/test-fixtures/OriginalSplitmixEntry.hs"]
  audited <- execute "splitmix-audit" [] "python3"
    (["scripts/audit-core.py","--entry","original-splitmix-entry:OriginalSplitmixEntry.sample",
      "--output",output </> "audit.json",entryPath] ++ [output </> name | (name,_) <- linked])
  audit <- readJson (output </> "audit.json")
  accepted <- field audit "accepted"
  unless accepted (fail "strict original splitmix audit rejected")
  splitmixBuilt <- execute "splitmix-native-build" [] ghc
    ["-O1","-package-db",native </> "packagedb/ghc-9.14.1","-package-id",unit,
     "compiler/test-fixtures/OriginalSplitmixNative.hs","-outputdir",output </> "splitmix-oracle-objects",
     "-o",output </> "splitmix-oracle"]
  splitmixOracle <- execute "splitmix-native-run" [] (output </> "splitmix-oracle") []
  unless (length (BSC.lines (commandStdout splitmixOracle)) == 8) (fail "original splitmix native row inventory differs")
  BS.writeFile (output </> "splitmix-native.tsv") (commandStdout splitmixOracle)
  nativeBuilt <- execute "native-build" [] clang
    ["-O1","-DTHC_NATIVE_ORACLE","compiler/test-fixtures/NativeGetEntropy.c","-o",output </> "oracle"]
  oracle <- execute "native-run" [] (output </> "oracle") []
  unless (length (BSC.lines (commandStdout oracle)) == 9) (fail "getentropy native row inventory differs")
  BS.writeFile (output </> "native.tsv") (commandStdout oracle)
  controlBuilt <- execute "control-build" [] clang
    ["--target=x86_64-unknown-linux-gnu","-O1","-fembed-bitcode","-shared","-fPIC",
     "compiler/test-fixtures/NativeGetEntropy.c","-lc","-o",output </> "control.so"]
  irBuilt <- execute "control-ir" [] clang
    ["--target=x86_64-unknown-linux-gnu","-O1","-emit-llvm","-S",
     "compiler/test-fixtures/NativeGetEntropy.c","-o",output </> "control.ll"]
  either fail pure . validateNativeEntropyIR "x86_64-unknown-linux-gnu" =<< readFile (output </> "control.ll")
  sourceHashes <- hashes root (map (makeRelative root) retained)
  inputHashes <- hashes root ["compiler/test-fixtures/NativeGetEntropy.c","compiler/test-fixtures/OriginalSplitmixNative.hs",
    "compiler/test-fixtures/OriginalSplitmixEntry.hs",
    "test/haskell-fixtures/GetEntropyFixtures.hs","test/haskell-fixtures/FixtureSupport.hs",
    "src/THC/Driver/PackageNative.hs","src/THC/Driver/NativeArgumentBridge.hs",
    "src/THC/Driver/NativeLibrarySources.hs","src/THC/Driver/GhcProxy.hs",
    "compiler/THC/Plugin.hs","compiler/THC/ForeignImportProvenance.hs","compiler/THC/Interface.hs",
    "scripts/audit-core.py","scripts/core_package_manifest.py"]
  artifactHashes <- hashes root ([relative </> path | path <-
    ["System.Random.SplitMix.Init.json","System.Random.SplitMix.json","System.Random.SplitMix32.json",
     "entry/units/u-original-splitmix-entry/OriginalSplitmixEntry.json",
     "audit.json","native.tsv","splitmix-native.tsv","control.so","control.ll"]] ++
    concatMap commandArtifacts [built,acquired,entryBuilt,audited,splitmixBuilt,splitmixOracle,nativeBuilt,oracle,controlBuilt,irBuilt])
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1::Int),"unit" .= unit,"nativeRows" .= (9::Int),"sourceHashes" .= sourceHashes,
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"driverSha256" .= driverHash,
     "commands" .= map commandRecord [built,acquired,entryBuilt,audited,splitmixBuilt,splitmixOracle,nativeBuilt,oracle,controlBuilt,irBuilt]]
  putStrLn "getentropy: original splitmix safe initializer; strict Core audit and 9 native status/bounds observations"
  where
    line bytes = case BSC.lines bytes of [value] -> BSC.unpack value; _ -> error "expected exactly one tool result"
    locate execute cabal target = line . commandStdout <$> execute
      ("locate-" ++ drop 4 target) [] cabal ["list-bin","--offline",target]
    files directory = do
      names <- sort <$> listDirectory directory
      concat <$> forM names (\name -> do
        let path = directory </> name
        nested <- doesDirectoryExist path
        if nested then files path else pure [path])
