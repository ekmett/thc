-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : InstalledCoreFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for installed core.
module InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore, prepareInstalledCoreUnits, prepareInstalledCoreWithForeign, prepareInstalledCoreWithForeignUnits, field, readJson) where

import Control.Monad (forM, unless)
import Data.Aeson (Value, FromJSON, decodeStrict', fromJSON, Result(..), object, (.=))
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import FixtureSupport (CommandResult(..), hashFile, runLogged, writeJson)
import qualified THC.Driver.Cache as Cache
import THC.Driver.CoreSymbols (publishCoreUnit)
import qualified THC.Driver.Installed as Installed
import qualified THC.Driver.InstalledForeign as Foreign
import qualified THC.Driver.Project as Project
import qualified THC.Driver.Wired as Wired
import System.Directory (copyFile, createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative)

field :: FromJSON a => Value -> String -> IO a
field (Aeson.Object fields) name = case KeyMap.lookup (Key.fromString name) fields of
  Just value -> case fromJSON value of Success result -> pure result; Error _ -> bad
  Nothing -> bad
  where bad = die ("Invalid installed fixture field: " ++ name)
field _ name = die ("Invalid installed fixture record: " ++ name)

readJson :: FilePath -> IO Value
readJson path = maybe (die ("Invalid JSON: " ++ path)) pure . decodeStrict' =<< BS.readFile path

data InstalledFixture = InstalledFixture
  { fixtureGhc :: FilePath, fixturePackages :: FilePath, fixtureArtifacts :: [FilePath]
  , fixtureCommands :: [CommandResult], fixtureContext :: Installed.InstalledContext
  , fixtureRegistration :: String }

-- Fixture orchestration only. Production Installed and Project own discovery,
-- complete-Core validation, native companion linkage, TargetLayout and ZIP/cache
-- provenance. Stock thin interfaces enter the same pinned provider as ordinary
-- project acquisition; explicit complete-Core/foreign profiles remain strict.
prepareInstalledCore :: FilePath -> FilePath -> IO InstalledFixture
prepareInstalledCore root directory = prepareInstalledCoreUnits root directory []

-- Additional original library units use the same complete-Core acquisition and
-- dependency closure as ghc-internal; no source or synthetic binding substitute.
prepareInstalledCoreUnits :: FilePath -> FilePath -> [String] -> IO InstalledFixture

-- Opt into the production producer of genuine foreign registration annotations.
prepareInstalledCoreWithForeign :: FilePath -> FilePath -> FilePath -> IO InstalledFixture
prepareInstalledCoreWithForeign root directory source =
  prepareInstalledCoreProfile (Just source) root directory []

-- | Acquire the original owners needed by shared foreign fixture consumers.
prepareInstalledCoreWithForeignUnits :: FilePath -> FilePath -> FilePath -> [String] -> IO InstalledFixture
prepareInstalledCoreWithForeignUnits root directory source =
  prepareInstalledCoreProfile (Just source) root directory

prepareInstalledCoreUnits = prepareInstalledCoreProfile Nothing

prepareInstalledCoreProfile :: Maybe FilePath -> FilePath -> FilePath -> [String] -> IO InstalledFixture
prepareInstalledCoreProfile foreignSource root directory libraries = do
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  let run label program arguments = runLogged 600 root (directory </> "logs") label [] program arguments
      selection = ["exe:thc-interface", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
      packagePath = directory </> "installed/packages.json"
  version <- run "ghc-version" ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Installed fixture requires GHC 9.14.1")
  built <- run "helper-build" cabal ("build" : "exe:thc" : selection)
  located <- run "helper-location" cabal ("list-bin" : selection)
  helper <- case lines (BS.unpack (commandStdout located)) of
    [path] -> pure path
    _ -> die "Expected one selected-GHC thc-interface executable"
  driverLocated <- run "driver-location" cabal ("list-bin" : "exe:thc" : drop 1 selection)
  driver <- case lines (BS.unpack (commandStdout driverLocated)) of
    [path] -> pure path
    _ -> die "Expected one selected-GHC production thc executable"
  plan <- readJson (root </> "dist-newstyle/cache/plan.json")
  compilerId <- field plan "compiler-id" :: IO String
  abi <- field plan "compiler-abi" :: IO String
  arch <- field plan "arch" :: IO String
  os <- field plan "os" :: IO String
  unless (compilerId == "ghc-9.14.1") (die "Installed fixture helper selected a different compiler")
  -- A private interface view may contain genuinely rebuilt Core annotations
  -- without being a native ABI replacement. Only acquisition uses that view;
  -- the helper and native oracles continue to use the ordinary selected GHC.
  coreGhc <- lookupEnv "THC_INSTALLED_CORE_GHC"
  corePkg <- lookupEnv "THC_INSTALLED_CORE_GHC_PKG"
  (providerGhc, providerPkg, providerCommands) <- case (coreGhc, corePkg) of
    (Nothing, Nothing) -> pure (ghc, ghcPkg, [])
    (Just compiler, Just packageTool) -> do
      providerVersion <- run "core-provider-version" compiler ["--numeric-version"]
      unless (commandStdout providerVersion == commandStdout version)
        (die "Installed Core provider and native compiler versions differ")
      pure (compiler, packageTool, [providerVersion])
    _ -> die "Set THC_INSTALLED_CORE_GHC and THC_INSTALLED_CORE_GHC_PKG together"
  initialContext <- Installed.installedContext providerGhc providerPkg helper [] (object
    ["id" .= compilerId, "abi" .= abi, "platform" .= (arch ++ "-" ++ os),
     "way" .= ("dynamic-nonprofiling" :: String)])
  registration <- run "ghc-internal-unit" providerPkg
    ["--global", "--no-user-package-db", "field", "ghc-internal", "id", "--simple-output"]
  internal <- case BS.words (commandStdout registration) of
    [name] -> pure (BS.unpack name)
    _ -> die "Expected one selected ghc-internal registration"
  let discover seen [] = pure (reverse seen)
      discover seen (identifier:todo)
        | identifier `elem` map Installed.registeredId seen = discover seen todo
        | otherwise = do
            unit <- Installed.discoverInstalled initialContext identifier
            discover (unit:seen) (Installed.installedDepends unit ++ todo)
  additional <- forM libraries $ \library -> do
    registered <- run (library ++ "-unit") providerPkg
      ["--global", "--no-user-package-db", "field", library, "id", "--simple-output"]
    case BS.words (commandStdout registered) of
      [identifier] -> pure (BS.unpack identifier, registered)
      _ -> die ("Expected one selected registration: " ++ library)
  originalUnits <- discover [] (internal : map fst additional)
  cache <- Cache.coreCacheDirectory
  -- Fixture inventory changes do not alter the actual acquisition driver.
  driverHash <- hashFile driver
  selected <- case foreignSource of
    Nothing -> case coreGhc of
      Just _ -> pure initialContext
      Nothing -> do
        -- Missing Core, not arbitrary probe/link/identity failures, selects the
        -- shared production provider. Successful ordinary acquisitions remain
        -- available in the authentic installed-bundle cache for the loop below.
        let missing [] = pure False
            missing (unit:rest) = do
              result <- Project.prepareInstalledBundle cache (root </> directory </> "installed/staging")
                (root </> "src/driver/cbits/target-layout.c") driverHash initialContext unit
              case result of Left _ -> pure True; Right _ -> missing rest
        needed <- missing originalUnits
        if not needed then pure initialContext else do
          plugin <- readJson (root </> "build/compiler/plugin.json")
          pluginDb <- field plugin "packageDb"
          pluginUnit <- field plugin "unitId"
          pluginLibrary <- field plugin "sharedLibrary"
          Wired.preparePinnedInterfaces cache driverHash pluginDb pluginUnit pluginLibrary initialContext originalUnits
    Just source -> do
      plugin <- readJson (root </> "build/compiler/plugin.json")
      pluginDb <- field plugin "packageDb"
      pluginUnit <- field plugin "unitId"
      pluginLibrary <- field plugin "sharedLibrary"
      registeredLibrary <- field plugin "cabalSharedLibrary"
      Foreign.prepareForeignInterfaces
        (Foreign.ForeignCompiler ghc pluginDb pluginUnit pluginLibrary registeredLibrary driverHash)
        cache source initialContext originalUnits
  units <- mapM (Installed.discoverInstalled selected . Installed.registeredId) originalUnits
  Installed.validateReexports units
  internalRegistration <- case [Installed.registration unit | unit <- units,
                                Installed.registeredId unit == internal] of
    [value] -> pure value
    _ -> die "Installed fixture lost its selected ghc-internal registration"
  createDirectoryIfMissing True (root </> directory </> "installed/bundles")
  bundles <- forM units $ \unit -> do
    acquired <- Project.prepareInstalledBundle cache (root </> directory </> "installed/staging")
      (root </> "src/driver/cbits/target-layout.c") driverHash selected unit
    original <- case acquired of
      Right value -> pure value
      Left missing -> die ("Fixture requires complete-interface-core from the selected GHC: " ++
        Installed.missingUnit missing ++ ":" ++ Installed.missingModule missing ++
        " (" ++ Installed.missingInterface missing ++ "). No incomplete interface substitute is used.")
    let bundle = Project.installedBundle original
        destination = directory </> "installed/bundles" </> Installed.registeredId unit ++ ".zip"
    copyFile (Project.bundlePath bundle) (root </> destination)
    digest <- hashFile (root </> destination)
    unless (digest == Project.bundleHash bundle) (die "Installed fixture bundle changed while copying")
    let local = original {Project.installedBundle = bundle {Project.bundlePath = root </> destination}}
    records <- mapM (publishCoreUnit (root </> directory </> "installed") False) (Project.installedRecords unit local)
    publications <- fmap concat $ forM records $ \record -> do
      modules <- field record "modules" :: IO [Value]
      paths <- forM modules $ \value -> do
        compact <- field value "compact" :: IO Value
        makeRelative root <$> (field compact "path" :: IO FilePath)
      pure (paths ++ [directory </> "installed/unit-core/v3" </> digest </> "publication.json" | not (null paths)])
    pure (records, destination : publications)
  writeJson (root </> packagePath) $ object
    ["format" .= ("thc-core-packages" :: String), "schema" .= (1 :: Int),
     "ghc" .= ("9.14.1" :: String), "units" .= concatMap fst bundles]
  pure (InstalledFixture ghc packagePath (packagePath : concatMap snd bundles)
    ([version, built, located, driverLocated] ++ providerCommands ++ [registration] ++ map snd additional) selected internalRegistration)
