-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore, field, readJson) where

import Control.Monad (forM, unless)
import Data.Aeson (Value, FromJSON, decodeStrict', fromJSON, Result(..), object, (.=))
import qualified Data.Aeson as Aeson
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import FixtureSupport (CommandResult(..), hashFile, runLogged, writeJson)
import qualified THC.Driver.Cache as Cache
import qualified THC.Driver.Installed as Installed
import qualified THC.Driver.Project as Project
import System.Directory (copyFile, createDirectoryIfMissing)
import System.Environment (getExecutablePath, lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

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
  , fixtureCommands :: [CommandResult] }

-- Fixture orchestration only. Production Installed and Project own discovery,
-- complete-Core validation, native companion linkage, TargetLayout and ZIP/cache
-- provenance. A stock thin-interface compiler is an explicit missing prerequisite.
prepareInstalledCore :: FilePath -> FilePath -> IO InstalledFixture
prepareInstalledCore root directory = do
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  let run label program arguments = runLogged 600 root (directory </> "logs") label [] program arguments
      selection = ["exe:thc-interface", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
      packagePath = directory </> "installed/packages.json"
  version <- run "ghc-version" ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Installed fixture requires GHC 9.14.1")
  built <- run "helper-build" cabal ("build" : selection)
  located <- run "helper-location" cabal ("list-bin" : selection)
  helper <- case lines (BS.unpack (commandStdout located)) of
    [path] -> pure path
    _ -> die "Expected one selected-GHC thc-interface executable"
  plan <- readJson (root </> "dist-newstyle/cache/plan.json")
  compilerId <- field plan "compiler-id" :: IO String
  abi <- field plan "compiler-abi" :: IO String
  arch <- field plan "arch" :: IO String
  os <- field plan "os" :: IO String
  unless (compilerId == "ghc-9.14.1") (die "Installed fixture helper selected a different compiler")
  selected <- Installed.installedContext ghc ghcPkg helper [] (object
    ["id" .= compilerId, "abi" .= abi, "platform" .= (arch ++ "-" ++ os),
     "way" .= ("dynamic-nonprofiling" :: String)])
  registration <- run "ghc-internal-unit" ghcPkg
    ["--global", "--no-user-package-db", "field", "ghc-internal", "id", "--simple-output"]
  internal <- case BS.words (commandStdout registration) of
    [name] -> pure (BS.unpack name)
    _ -> die "Expected one selected ghc-internal registration"
  let discover seen [] = pure (reverse seen)
      discover seen (identifier:todo)
        | identifier `elem` map Installed.registeredId seen = discover seen todo
        | otherwise = do
            unit <- Installed.discoverInstalled selected identifier
            discover (unit:seen) (Installed.installedDepends unit ++ todo)
  units <- discover [] [internal]
  Installed.validateReexports units
  cache <- Cache.coreCacheDirectory
  driverHash <- hashFile =<< getExecutablePath
  createDirectoryIfMissing True (root </> directory </> "installed/bundles")
  bundles <- forM units $ \unit -> do
    acquired <- Project.prepareInstalledBundle cache (root </> directory </> "installed/staging")
      (root </> "compiler/target-layout.c") driverHash selected unit
    original <- case acquired of
      Right value -> pure value
      Left missing -> die ("Fixture requires complete-interface-core from the selected GHC: " ++
        Installed.missingUnit missing ++ ":" ++ Installed.missingModule missing ++
        " (" ++ Installed.missingInterface missing ++ "). No incomplete pinned-source fallback is used.")
    let bundle = Project.installedBundle original
        destination = directory </> "installed/bundles" </> Installed.registeredId unit ++ ".zip"
    copyFile (Project.bundlePath bundle) (root </> destination)
    digest <- hashFile (root </> destination)
    unless (digest == Project.bundleHash bundle) (die "Installed fixture bundle changed while copying")
    let local = original {Project.installedBundle = bundle {Project.bundlePath = root </> destination}}
    pure (Project.installedRecords unit local, destination)
  writeJson (root </> packagePath) $ object
    ["format" .= ("thc-core-packages" :: String), "schema" .= (1 :: Int),
     "ghc" .= ("9.14.1" :: String), "units" .= concatMap fst bundles]
  pure (InstalledFixture ghc packagePath (packagePath : map snd bundles) [version, built, located, registration])
