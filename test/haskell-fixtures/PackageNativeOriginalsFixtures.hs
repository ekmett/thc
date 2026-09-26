-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module PackageNativeOriginalsFixtures (preparePackageNativeOriginals) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value, object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isInfixOf, sort)
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv, unsetEnv)
import System.FilePath
import System.IO.Error (tryIOError)
import THC.Driver.GhcProxy (ghcProxyCommand)
import THC.Driver.NativeLibrarySources (zlibChecksumSources)
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
  built <- execute "driver-build" [] cabal ["build","--offline","-j2","exe:thc","lib:thc","exe:thc-interface"]
  driver <- locate execute cabal "exe:thc"
  helper <- locate execute cabal "exe:thc-interface"
  driverHash <- hashFile driver
  let pluginDb = root </> "dist-newstyle/packagedb/ghc-9.14.1"
  libdir <- line . commandStdout <$> execute "ghc-libdir" [] ghc ["--print-libdir"]
  plugin <- line . commandStdout <$> execute "plugin-unit" [] ghcPkg
    ["--package-db",pluginDb,"field","thc","id","--simple-output"]
  sourceFiles <- files source
  sourceHashes <- hashes root (map (makeRelative root) (sourceFiles ++ retainedErf))
  let key = take 16 driverHash
      native = output </> ("native-" ++ key)
      capture = output </> ("capture-" ++ key)
      pieces = output </> ("pieces-" ++ key)
      wrapper = output </> ("ghc-proxy-" ++ key) <.> "sh"
      project = output </> "digest.project"
      unit = "digest-0.0.2.1-inplace"
  writeFile project $ unlines ["packages: " ++ show source ++ " " ++ show erfSource,"jobs: 1","tests: False","benchmarks: False"]
  writeFile wrapper ("#!/bin/sh\n" ++ ghcProxyCommand)
  permissions <- getPermissions wrapper
  setPermissions wrapper permissions {executable=True}
  let environment = [("THC_PROXY_DRIVER",driver),("THC_PROXY_ROOT",root),("THC_PROXY_GHC",ghc),
        ("THC_PROXY_GLOBAL_UNITS",unlines [unit,erfUnit]),("THC_PROXY_CAPTURE",capture),("THC_PROXY_PLUGIN_DB",pluginDb),
        ("THC_PROXY_PLUGIN_UNIT",plugin),("THC_PROXY_INTERFACE_HELPER",helper),
        ("THC_PROXY_INTERFACE_LIBDIR",libdir),("THC_PROXY_NATIVE_PIECES",pieces)]
  acquired <- execute "digest-acquisition" environment cabal
    ["build","--offline","--project-file=" ++ project,"--builddir=" ++ native,
     "--with-compiler=" ++ wrapper,"lib:digest","lib:erf"]
  plan <- readJson (native </> "cache/plan.json")
  planned <- field plan "install-plan" :: IO [Value]
  names <- mapM (\value -> field value "id") planned
  unless (all (`elem` (names :: [String])) [unit,erfUnit]) (fail "original package Cabal unit differs")
  modulePaths <- filter ((== ".json") . takeExtension) <$> files (capture </> unit </> "core")
  modules <- forM modulePaths $ \path -> (,) (takeFileName path) <$> BS.readFile path
  unless (sort (map fst modules) == ["Data.Digest.Adler32.json","Data.Digest.CRC32.json","Data.Digest.CRC32C.json"])
    (fail "original digest retained module inventory differs")
  linked <- finishPackageNative pieces (capture </> unit) unit Nothing modules
  let linkedDirectory = output </> "linked" </> unit
  createDirectoryIfMissing True linkedDirectory
  forM_ linked $ \(name,bytes) -> BS.writeFile (linkedDirectory </> name) bytes
  erfModulePaths <- filter ((== ".json") . takeExtension) <$> files (capture </> erfUnit </> "core")
  erfModules <- forM erfModulePaths $ \path -> (,) (takeFileName path) <$> BS.readFile path
  unless (map fst erfModules == ["Data.Number.Erf.json"]) (fail "original erf retained module inventory differs")
  erfLinked <- finishPackageNative pieces (capture </> erfUnit) erfUnit Nothing erfModules
  let erfLinkedDirectory = output </> "linked" </> erfUnit
  createDirectoryIfMissing True erfLinkedDirectory
  forM_ erfLinked $ \(name,bytes) -> BS.writeFile (erfLinkedDirectory </> name) bytes
  compiled <- execute "digest-native-build" [] ghc
    ["-O1","-package-db",native </> "packagedb/ghc-9.14.1","-package-id",unit,
     "compiler/test-fixtures/OriginalDigestNative.hs","-outputdir",output </> "oracle-objects",
     "-o",output </> "digest-oracle"]
  oracle <- execute "digest-native-run" [] (output </> "digest-oracle") []
  unless (length (BSC.lines (commandStdout oracle)) == 270) (fail "original digest native row inventory differs")
  BS.writeFile (output </> "digest-native.tsv") (commandStdout oracle)
  erfCompiled <- execute "erf-native-build" [] ghc
    ["-O1","-package-db",native </> "packagedb/ghc-9.14.1","-package-id",erfUnit,
     "compiler/test-fixtures/OriginalErfNative.hs","-outputdir",output </> "erf-oracle-objects",
     "-o",output </> "erf-oracle"]
  erfOracle <- execute "erf-native-run" [] (output </> "erf-oracle") []
  unless (length (BSC.lines (commandStdout erfOracle)) == 88) (fail "original erf native row inventory differs")
  BS.writeFile (output </> "erf-native.tsv") (commandStdout erfOracle)
  let entryOutput = output </> "erf-entry"
  createDirectoryIfMissing True entryOutput
  entryCompiled <- execute "erf-entry-export" [] ghc
    ["-O1","-c","-fforce-recomp","-this-unit-id","original-erf-entry",
     "-package-db",native </> "packagedb/ghc-9.14.1","-package-id",erfUnit,
     "-package-db",pluginDb,"-plugin-package-id",plugin,"-fplugin=THC.Plugin","-fplugin-trustworthy",
     "-fplugin-opt=THC.Plugin:" ++ entryOutput,"-fplugin-opt=THC.Plugin:post-tidy",
     "-fplugin-opt=THC.Plugin:unit-qualified","-fplugin-opt=THC.Plugin:foreign-import-provenance",
     "-fwrite-if-simplified-core","-dcore-lint","compiler/test-fixtures/OriginalErfEntry.hs",
     "-outputdir",entryOutput]
  audited <- execute "erf-entry-audit" [] "python3"
    (["scripts/audit-core.py","--output",output </> "erf-audit.json"] ++
     concatMap (\name -> ["--entry","original-erf-entry:OriginalErfEntry." ++ name])
       ["erfDouble","erfcDouble","erfFloat","erfcFloat"] ++
     [erfLinkedDirectory </> "Data.Number.Erf.json",entryOutput </> "units/u-original-erf-entry/OriginalErfEntry.json"])
  inputs <- hashes root ["compiler/test-fixtures/OriginalDigestNative.hs",
    "compiler/test-fixtures/OriginalErfNative.hs","compiler/test-fixtures/OriginalErfEntry.hs",
    "test/haskell-fixtures/PackageNativeOriginalsFixtures.hs","src/THC/Driver/PackageNative.hs",
    "src/THC/Driver/NativeLibrarySources.hs","src/THC/Driver/GhcProxy.hs",
    "scripts/audit-core.py","scripts/core_package_manifest.py","scripts/core-capabilities.json"]
  artifacts <- hashes root ([relative </> "digest-native.tsv",relative </> "erf-native.tsv",
    relative </> "erf-entry/units/u-original-erf-entry/OriginalErfEntry.json",relative </> "erf-audit.json"] ++
    [relative </> "linked" </> unit </> name | (name,_) <- linked] ++
    [relative </> "linked" </> erfUnit </> name | (name,_) <- erfLinked])
  implementations <- zlibChecksumSources root
  implementation <- case implementations of
    (_,sourceText):_ -> pure sourceText
    [] -> fail "pinned zlib provider returned no implementations"
  let negative = output </> "negative-header"
  createDirectoryIfMissing True negative
  writeFile (negative </> "zlib.h") "#define ZLIB_VERNUM 0x1300\n"
  writeFile (negative </> "mismatch.c") implementation
  clang <- maybe "clang" id <$> lookupEnv "THC_CLANG"
  badHeader <- runLoggedExpect 1 60 root (relative </> "logs") "mismatched-zlib-header" [] clang
    ["-I" ++ negative,"-c",negative </> "mismatch.c","-o",negative </> "mismatch.o"]
  unless ("requires the configured zlib 1.2.11 header" `BSC.isInfixOf` commandStderr badHeader)
    (fail "configured zlib version negative control did not reach the provider's rejection")
  let altered = output </> "negative-source"
      sourceTree = root </> "compiler/pinned-zlib/1.2.11"
  pinned <- files sourceTree
  forM_ pinned $ \path -> do
    let destination = altered </> makeRelative root path
    createDirectoryIfMissing True (takeDirectory destination)
    copyFile path destination
  appendFile (altered </> "compiler/pinned-zlib/1.2.11/adler32.c") "\n/* altered negative-control source */\n"
  rejected <- tryIOError (zlibChecksumSources altered)
  case rejected of
    Left problem | "pinned zlib checksum source differs" `isInfixOf` show problem -> pure ()
    _ -> fail "changed upstream checksum source was not rejected"
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1::Int),"scope" .= ("original-package-foreign-adapters"::String),
     "unit" .= unit,"nativeRows" .= (270::Int),"erfNativeRows" .= (88::Int),"driverSha256" .= driverHash,"inputHashes" .= inputs,
     "sourceHashes" .= sourceHashes,"artifactHashes" .= artifacts,
     "rejectedChangedSource" .= True,"rejectedHeaderMismatch" .= True,
     "commands" .= map commandRecord [built,acquired,compiled,oracle,erfCompiled,erfOracle,entryCompiled,audited,badHeader]]
  putStrLn "package-native-originals: original digest C++/zlib and erf safe/libm acquisition; 270 + 88 native observations"
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
