-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : HashableFfiFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for hashable ffi.
module HashableFfiFixtures (prepareHashableFfi) where

import Control.Monad (unless, when)
import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString.Char8 as BS
import Data.List (isPrefixOf, sort)
import qualified Data.Text as T
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath

-- The production project driver acquires the unchanged Hackage dependency.
-- Its own Cabal executable is the native oracle; no hand-written hash model,
-- replacement package source, or modified foreign-call descriptor is involved.
prepareHashableFfi :: FilePath -> IO ()
prepareHashableFfi root = do
  let directory = "build/hashable-ffi"
      fixture = "test/fixtures/run-hashable-ffi"
      acquired = directory </> "acquired"
      manifest = root </> directory </> "manifest.json"
      execute = runLogged 2400 root (directory </> "logs")
      sources = ["cabal.project", "run-hashable-ffi.cabal", "src/HashableProbe.hs", "app/Main.hs"]
  createDirectoryIfMissing True (root </> directory)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- selected "THC_INSTALLED_CORE_GHC" "GHC" "ghc"
  ghcPkg <- selected "THC_INSTALLED_CORE_GHC_PKG" "GHC_PKG" "ghc-pkg"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  runtime <- maybe (pure (root </> "build/install/thc/bin/thc")) canonicalizePath =<< lookupEnv "THC_TEST_RUNTIME"
  sourceRoot <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
  let selection = ["exe:thc", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
  built <- execute "driver-build" [] cabal ("build" : selection)
  located <- execute "driver-location" [] cabal ("list-bin" : selection)
  driver <- case BS.lines (commandStdout located) of
    [path] -> pure (BS.unpack path)
    _ -> die "hashable-ffi: expected one selected-GHC driver"
  managed <- execute "thc-run" [("THC_BACKEND", "bytecode")] driver
    (["run", "--verify-artifacts", "--project-dir", root </> fixture, "run-hashable-ffi:exe:oracle", "--thc-root", root,
      "--runtime", runtime, "--dist-dir", root </> acquired, "--installed-core", "required",
      "--with-ghc", ghc, "--with-ghc-pkg", ghcPkg] ++
      maybe [] (\path -> ["--ghc-source", path]) sourceRoot)
  plan <- readJson (root </> acquired </> "native/cache/plan.json")
  units <- field plan "install-plan" :: IO [Value]
  dependency <- unique "hashable dependency" (matching "pkg-name" "hashable" units)
  version <- field dependency "pkg-version" :: IO String
  unless (version == "1.5.1.0") (die "hashable-ffi: wrong Hashable version")
  flags <- field dependency "flags"
  randomized <- field flags "random-initial-seed" :: IO Bool
  nativeArch <- field flags "arch-native" :: IO Bool
  unless (not randomized && not nativeArch) (die "hashable-ffi: deterministic portable Hashable flags required")
  dependencyUnit <- field dependency "id" :: IO String
  library <- unique "probe library" (matching "component-name" "lib" (matching "pkg-name" "run-hashable-ffi" units))
  probeUnit <- field library "id" :: IO String
  oracle <- unique "native oracle" (matching "component-name" "exe:oracle" units)
  binary <- field oracle "bin-file"
  native <- execute "native-oracle" [] binary []
  unless (commandStdout managed == commandStdout native)
    (die "hashable-ffi: ordinary THC execution differs from the matching native oracle")
  rows <- mapM observation (BS.lines (commandStdout native))
  unless (length rows == 300) (die "hashable-ffi: expected 300 native observations")
  audit <- readJson (root </> acquired </> "audit.json")
  accepted <- field audit "accepted"
  unless accepted (die "hashable-ffi: production audit did not accept original executable")
  packages <- readJson (root </> acquired </> "packages.json")
  records <- field packages "units" :: IO [Value]
  -- Rehome unchanged, hash-checked artifacts, never replace exported Core. This
  -- makes the fixture independent of later eviction from acquisition caches.
  retained <- mapM (retainUnitArtifacts root (directory </> "bundles")) records
  let packagePath = directory </> "packages.json"
  writeJson (root </> packagePath) (replace "units" (toJSON (map fst retained)) packages)
  plugin <- listDirectory (root </> "src/compiler/THC")
  driverSources <- listDirectory (root </> "src/driver/THC/Driver")
  auditors <- listDirectory (root </> "bin")
  inputs <- hashes root $ sort $ map (fixture </>) sources ++
    ["thc.cabal", "test/haskell-fixtures/HashableFfiFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
     "test/haskell-fixtures/InstalledCoreFixtures.hs", "test/haskell-fixtures/Main.hs",
     "bin/build-compiler.sh", "bin/toolchain.sh", "bin/plugin.py", "src/compiler/interface/Main.hs",
     "bin/audit-core.py", "bin/core-capabilities.json"] ++
    ["src/compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
    ["src/driver/THC/Driver" </> file | file <- driverSources, takeExtension file == ".hs"] ++
    ["bin" </> file | file <- auditors, "core_" `isPrefixOf` file, takeExtension file == ".py"]
  let commands = [built, located, managed, native]
  artifacts <- hashes root ([packagePath, acquired </> "packages.json", acquired </> "audit.json",
    acquired </> "native/cache/plan.json"] ++ concatMap snd retained ++ concatMap commandArtifacts commands)
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "strictAccepted" .= True, "runtimeVerified" .= True,
     "hashableVersion" .= version, "hashableUnit" .= dependencyUnit, "probeUnit" .= probeUnit,
     "randomInitialSeed" .= False, "archNative" .= False, "nativeRows" .= length rows,
     "packages" .= packagePath, "observations" .= rows, "inputHashes" .= inputs,
     "artifactHashes" .= artifacts, "commands" .= map commandRecord commands]
  putStrLn "hashable-ffi: original Hashable 1.5.1.0, 300 native/THC matches across five byte-backed instances"
  where
    selected preferred ordinary fallback = do
      override <- lookupEnv preferred
      maybe (maybe fallback id <$> lookupEnv ordinary) pure override
    matching key expected values = [value | value@(Object fields) <- values,
      KM.lookup (Key.fromString key) fields == Just (String (T.pack expected))]
    unique _ [value] = pure value
    unique label _ = die ("hashable-ffi: expected one " ++ label)
    replace key value (Object fields) = Object (KM.insert (Key.fromString key) value fields)
    replace _ _ _ = error "hashable-ffi: expected JSON object"
    observation line = case splitTab (BS.unpack line) of
      [entry, salt, choice, result] -> do
        unless (entry `elem` ["strictText", "strictBytes", "shortBytes", "lazyText", "lazyBytes"])
          (die "hashable-ffi: unexpected native probe")
        mapM_ (maybe (die "hashable-ffi: invalid native integer") (const (pure ())) . readInteger) [salt, choice, result]
        pure (object ["entry" .= entry, "arguments" .= [salt, choice], "result" .= result])
      _ -> die "hashable-ffi: malformed native oracle row"
