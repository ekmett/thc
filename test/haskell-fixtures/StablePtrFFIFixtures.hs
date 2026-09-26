-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module StablePtrFFIFixtures (prepareStablePtrFFI) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString.Char8 as BS
import qualified Data.Text as T
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath

-- Ordinary Foreign.StablePtr and genuine C imports, never a THC-specific API.
prepareStablePtrFFI :: FilePath -> IO ()
prepareStablePtrFFI root = do
  let directory = "build/stableptr-ffi"
      fixture = "test/fixtures/run-stableptr-ffi"
      project = directory </> "project"
      manifest = root </> directory </> "manifest.json"
      execute = runLogged 1800 root (directory </> "logs")
      sources = ["src/StableForeign.hs","app/Main.hs","cbits/stable.c","cbits/stable.h"]
      packageFile = project </> "stableptr-ffi.cabal"
      projectFile = project </> "cabal.project"
  createDirectoryIfMissing True (root </> directory)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- selected "THC_INSTALLED_CORE_GHC" "GHC" "ghc"
  ghcPkg <- selected "THC_INSTALLED_CORE_GHC_PKG" "GHC_PKG" "ghc-pkg"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  ccName <- maybe "clang" id <$> lookupEnv "THC_CLANG"
  cc <- findExecutable ccName >>= maybe (die "stableptr-ffi requires configured Clang") canonicalizePath
  runtime <- maybe (pure (root </> "build/install/thc/bin/thc")) canonicalizePath =<< lookupEnv "THC_TEST_RUNTIME"
  sourceRoot <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
  let driverSelection = ["exe:thc","--offline","--with-compiler=" ++ ghc,"--with-hc-pkg=" ++ ghcPkg]
  built <- execute "driver-build" [] cabal ("build":driverSelection)
  located <- execute "driver-location" [] cabal ("list-bin":driverSelection)
  driver <- case BS.lines (commandStdout located) of
    [path] -> pure (BS.unpack path)
    _ -> die "Expected one selected-GHC thc executable"
  forM_ sources $ \source -> do
    createDirectoryIfMissing True (takeDirectory (root </> project </> source))
    copyFile (root </> fixture </> source) (root </> project </> source)
  writeFile (root </> projectFile) "packages: .\n"
  writeFile (root </> packageFile) $ unlines
    ["cabal-version: 3.0","name: stableptr-ffi","version: 0.1.0.0","build-type: Simple",
     "library","  exposed-modules: StableForeign","  hs-source-dirs: src","  include-dirs: cbits",
     "  c-sources: cbits/stable.c","  cc-options: -std=c11",
     "  ghc-options: -O2 -pgmc " ++ show cc,"  build-depends: base","  default-language: Haskell2010",
     "executable oracle","  main-is: Main.hs","  hs-source-dirs: app","  ghc-options: -O2",
     "  build-depends: base, stableptr-ffi","  default-language: Haskell2010"]
  managed <- execute "thc-run" [("THC_BACKEND","bytecode")] driver
    (["run",root </> project,"--exe","oracle","--thc-root",root,"--runtime",runtime,
      "--dist-dir",root </> directory,"--installed-core","required","--with-ghc",ghc,"--with-ghc-pkg",ghcPkg] ++
      maybe [] (\path -> ["--ghc-source",path]) sourceRoot)
  plan <- readJson (root </> directory </> "native/cache/plan.json")
  units <- field plan "install-plan" :: IO [Value]
  component <- unique "oracle" =<< matching "component-name" "exe:oracle" units
  binary <- field component "bin-file"
  native <- execute "native-run" [] binary []
  unless (commandStdout managed == commandStdout native) (die "stableptr-ffi: ordinary THC/native stdout differs")
  rows <- mapM observation (BS.lines (commandStdout native))
  unless (length rows == 12) (die "stableptr-ffi: native oracle inventory differs")
  library <- unique "library" =<< matching "component-name" "lib" units
  unit <- field library "id" :: IO String
  audit <- readJson (root </> directory </> "audit.json")
  accepted <- field audit "accepted"
  unless accepted (die "stableptr-ffi: production audit did not accept original executable")
  packages <- readJson (root </> directory </> "packages.json")
  records <- field packages "units" :: IO [Value]
  forM_ records $ \record -> do
    bundle <- field record "bundle"
    path <- field bundle "path"
    expected <- field bundle "sha256"
    actual <- hashFile path
    unless (actual == expected) (die "stableptr-ffi: acquired bundle hash differs")
  inputs <- hashes root $ [fixture </> source | source <- sources] ++
    ["test/haskell-fixtures/StablePtrFFIFixtures.hs","src/THC/Driver/PackageNative.hs",
     "src/main/kotlin/thc/runtime/StablePointers.kt","src/main/kotlin/thc/runtime/PackageScalarAccess.kt"]
  artifacts <- hashes root $ [directory </> "packages.json",directory </> "audit.json",projectFile,packageFile] ++
    [project </> source | source <- sources] ++ concatMap commandArtifacts [built,located,managed,native]
  writeJson manifest $ object ["schema" .= (1::Int),"strictAccepted" .= True,"runtimeVerified" .= True,
    "nativeRows" .= (12::Int),"unit" .= unit,"observations" .= rows,
    "inputHashes" .= inputs,"artifactHashes" .= artifacts,
    "commands" .= map commandRecord [built,located,managed,native]]
  putStrLn "stableptr-ffi: ordinary Foreign.StablePtr ccall/capi, 12 matching GHC/THC observations"
  where
    selected preferred ordinary fallback = do
      override <- lookupEnv preferred
      maybe (maybe fallback id <$> lookupEnv ordinary) pure override
    matching key expected values = pure [value | value@(Object fields) <- values,
      KM.lookup (Key.fromString key) fields == Just (String (T.pack expected))]
    unique _ [value] = pure value
    unique label _ = die ("stableptr-ffi: expected one " ++ label)
    observation line = case splitTab (BS.unpack line) of
      [entry,argument,result] -> do
        _ <- maybe (die "invalid StablePtr argument") pure (readInteger argument)
        _ <- maybe (die "invalid StablePtr result") pure (readInteger result)
        pure $ object ["entry" .= entry,"argument" .= argument,"result" .= result]
      _ -> die "stableptr-ffi: malformed native observation"
