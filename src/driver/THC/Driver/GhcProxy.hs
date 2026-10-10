-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.Driver.GhcProxy
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC utilities and host filesystem/process services
--
-- Cabal invokes this transparent compiler for ordinary owned native builds
-- and private store builds.
-- Selected THC-only runnable units omit their native final links. A second
-- invocation exports Core while
-- Cabal's unpacked source and generated files still exist.
module THC.Driver.GhcProxy (runGhcProxy, ghcProxyCommand, ghcProxyWindowsCommand, directPlugin,
                           coreReplayArguments) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (eitherDecodeStrict')
import Data.List (stripPrefix)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import qualified Data.Text.Encoding as Text
import qualified Distribution.InstalledPackageInfo as Package
import Distribution.Pretty (prettyShow)
import System.Directory (createDirectory, createDirectoryIfMissing, doesDirectoryExist,
                         doesFileExist, removeFile)
import GHC.ResponseFile (expandResponse)
import THC.Driver.NativeLibrarySources (nativePackageOptions)
import THC.Driver.NativeRecipe (captureNativeRecipe)
import THC.Driver.PackageNative (captureNativeObject, captureNativeComponent, capturePackageNative)
import System.Environment (getEnv, lookupEnv)
import System.Exit (ExitCode(..), exitWith)
import System.FilePath ((</>), takeDirectory)
import System.IO (hClose, openTempFile)
import System.Process (rawSystem, readProcess)

-- Stop the driver's native RTS before getArgs: these are the compiler's RTS
-- options, and must reach both the native compile and Core replay unchanged.
ghcProxyCommand :: String
ghcProxyCommand = "exec \"$THC_PROXY_DRIVER\" --RTS ghc-proxy \"$@\"\n"

-- System.Process/Cabal execute .cmd programs through the native Windows
-- command processor. Keep compiler RTS options behind the driver's --RTS.
ghcProxyWindowsCommand :: String
ghcProxyWindowsCommand = "@echo off\n@\"%THC_PROXY_DRIVER%\" --RTS ghc-proxy %*\nexit /b %errorlevel%\n"

runGhcProxy :: [String] -> IO ()
runGhcProxy arguments = do
  compiler <- getEnv "THC_PROXY_GHC"
  options <- expandResponse arguments
  noLinkUnits <- maybe [] lines <$> lookupEnv "THC_PROXY_NO_LINK_UNIT"
  let noLink = ["-no-link" | "--make" `elem` options, Just unit <- [valueAfter "-this-unit-id" options],
                            not (null unit), unit `elem` noLinkUnits]
  guestPlugin <- if null noLink then pure [] else do
    capture <- getEnv "THC_PROXY_CAPTURE"
    pluginDb <- getEnv "THC_PROXY_PLUGIN_DB"
    pluginUnit <- getEnv "THC_PROXY_PLUGIN_UNIT"
    pluginLibrary <- getEnv "THC_PROXY_PLUGIN_LIBRARY"
    let core = capture </> "guest-core"
    createDirectoryIfMissing True core
    -- JavaScript declarations need the parser rewrite even in Cabal's
    -- first compilation. Direct loading avoids linking guest dependencies.
    pure ["-package-db", pluginDb, "-fplugin-trustworthy",
          directPlugin pluginLibrary pluginUnit
            [core, "post-tidy", "unit-qualified", "foreign-import-provenance"] options]
  let added = noLink ++ guestPlugin
      nativeOptions = options ++ added
  native <- rawSystem compiler (arguments ++ added)
  when (native /= ExitSuccess) (exitWith native)
  receipts <- lookupEnv "THC_PROXY_NATIVE_RECIPES"
  mapM_ (\directory -> captureNativeRecipe directory compiler nativeOptions) receipts
  nativePieces <- lookupEnv "THC_PROXY_NATIVE_PIECES"
  mapM_ (\directory -> captureNativeComponent directory nativeOptions) nativePieces
  mapM_ (\directory -> captureNativeObject directory compiler nativeOptions) nativePieces
  targetUnits <- maybe [] lines <$> lookupEnv "THC_PROXY_GLOBAL_UNITS"
  originalBuild <- (== Just "1") <$> lookupEnv "THC_PROXY_ORIGINAL_BUILD"
  case ("--make" `elem` options, valueAfter "-this-unit-id" options) of
    -- A native signature-only compilation is not a completed executable Core
    -- witness. Optional original-build capture leaves it to final acquisition;
    -- the explicit isolated exporter retains its existing signature policy.
    (True, Just unit) | unit `elem` targetUnits,
        not originalBuild || "-fno-code" `notElem` options -> do
      capture <- getEnv "THC_PROXY_CAPTURE"
      pluginDb <- getEnv "THC_PROXY_PLUGIN_DB"
      pluginUnit <- getEnv "THC_PROXY_PLUGIN_UNIT"
      pluginLibrary <- getEnv "THC_PROXY_PLUGIN_LIBRARY"
      let root = capture </> unit
          core = root </> "core"
          objects = root </> "objects"
          ready = root </> "interfaces-ready"
          complete = root </> "capture-complete"
      createDirectoryIfMissing True core
      createDirectoryIfMissing True objects
      previous <- doesFileExist ready
      when previous (removeFile ready)
      when originalBuild $ do
        completed <- doesFileExist complete
        when completed (removeFile complete)
      view <- lookupEnv "THC_PROXY_CORE_LIBDIR"
      replayArguments <- case view of
        Nothing -> pure []
        Just libdir -> do
          packageTool <- maybe (takeDirectory compiler </> "ghc-pkg") id <$> lookupEnv "THC_PROXY_GHC_PKG"
          selected <- maybe "[]" id <$> lookupEnv "THC_PROXY_CORE_DATABASES"
          databases <- either (fail . ("invalid Core database stack: " ++)) pure
            (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack selected)) :: Either String [FilePath])
          supplied <- maybe "{}" id <$> lookupEnv "THC_PROXY_CORE_INTERFACES"
          warm <- either (fail . ("invalid Core interface map: " ++)) pure
            (eitherDecodeStrict' (Text.encodeUtf8 (Text.pack supplied)) :: Either String (Map.Map String FilePath))
          completed <- fmap concat $ forM targetUnits $ \owner -> do
            present <- doesFileExist (capture </> owner </> "interfaces-ready")
            pure [(owner, capture </> owner </> "objects") | present]
          let interfaces = Map.delete unit (Map.union (Map.fromList completed) warm)
          coreReplayArguments packageTool libdir databases root (Map.toList interfaces) options
      exported <- rawSystem compiler (arguments ++
        replayArguments ++
        ["-no-link", "-fforce-recomp", "-outputdir", objects, "-odir", objects,
         "-hidir", objects, "-hiedir", objects </> "hie", "-stubdir", objects,
         "-package-db", pluginDb, "-fplugin-trustworthy",
         directPlugin pluginLibrary pluginUnit
           [core, "post-tidy", "unit-qualified", "source-notes", "foreign-import-provenance",
            "foreign-export-associations", "foreign-export-registration"] options,
         -- Adding -g only to the replay can change CSE/tidied helper names.
         -- Preserve caller debug flags for native compilation and TH.
         "-fwrite-if-simplified-core",
         "-dcore-lint"])
      unless (exported == ExitSuccess) (exitWith exported)
      helper <- lookupEnv "THC_PROXY_INTERFACE_HELPER"
      libdir <- lookupEnv "THC_PROXY_INTERFACE_LIBDIR"
      case (helper,libdir) of
        (Just executable,Just selectedLibdir) ->
          getEnv "THC_PROXY_ROOT" >>= \repository ->
            capturePackageNative repository executable selectedLibdir compiler (options ++ replayArguments) unit root
        _ -> pure ()
      forM_ view $ \_ -> writeFile ready ""
      when originalBuild (writeFile complete "")
    _ -> pure ()

-- | Add a boot-library view and completed source-package interfaces only to a
-- Core replay. The caller owns the scratch directory until GHC exits. Each
-- invocation gets its own immutable package database, so parallel Cabal builds
-- never recache a database another compiler is using. Native registrations
-- retain their original identities, dependencies and libraries.
coreReplayArguments :: FilePath -> FilePath -> [FilePath] -> FilePath -> [(String, FilePath)] -> [String] -> IO [String]
coreReplayArguments packageTool libdir databases scratch interfaces arguments = do
  options <- expandResponse arguments
  let boot = ["-B" ++ libdir] ++ concatMap (\database -> ["-package-db", database]) databases
      stack = map ("--package-db=" ++) databases ++ nativePackageOptions options
  if null interfaces || null stack then pure boot else do
    dumped <- readProcess packageTool
      (["--global-package-db=" ++ libdir </> "package.conf.d", "--expand-pkgroot"] ++ stack ++ ["dump"]) ""
    records <- mapM parseRegistration (splitRegistrations (lines dumped))
    -- ghc-pkg dumps the stack from highest to lowest priority. Preserve the
    -- first registration of each exact unit, as GHC does for imports.
    let registered = Map.fromList [(prettyShow (Package.installedUnitId info), info) | info <- reverse records]
        selected = [(info, directory) | (owner, directory) <- interfaces,
                    Just info <- [Map.lookup owner registered]]
    if null selected then pure boot else do
      createDirectoryIfMissing True scratch
      (database, handle) <- openTempFile scratch "core-packages-"
      hClose handle
      removeFile database
      createDirectory database
      forM_ (zip [0 :: Int ..] selected) $ \(index, (info, directory)) -> do
        exists <- doesDirectoryExist directory
        unless exists (fail ("missing completed Core interfaces: " ++ directory))
        writeFile (database </> show index ++ ".conf")
          (Package.showInstalledPackageInfo (info { Package.importDirs = [directory] }))
      _ <- readProcess packageTool ["--package-db=" ++ database, "recache"] ""
      pure (boot ++ ["-package-db", database])
  where
    parseRegistration value = either (fail . show) (pure . snd)
      (Package.parseInstalledPackageInfo (Text.encodeUtf8 (Text.pack value)))
    splitRegistrations [] = []
    splitRegistrations input =
      let (record, rest) = break (== "---") input
      in [unlines record | any (not . null) record] ++
         case rest of [] -> []; _:remaining -> splitRegistrations remaining

-- ExternalPluginSpec owns its options: GHC does not merge pluginModNameOpts.
-- Keep the output directory first, then the required policy and caller options.
directPlugin :: FilePath -> String -> [String] -> [String] -> String
directPlugin library unit required arguments =
  "-fplugin-library=" ++ library ++ ";" ++ unit ++ ";THC.Plugin;" ++ show (required ++ options arguments)
  where
    options [] = []
    options ("-fplugin-opt":value:rest) = selected value ++ options rest
    options (argument:rest) = maybe [] selected (stripPrefix "-fplugin-opt=" argument) ++ options rest
    selected "THC.Plugin" = [""]
    selected value = maybe [] (:[]) (stripPrefix "THC.Plugin:" value)

valueAfter :: String -> [String] -> Maybe String
valueAfter wanted = lastValue Nothing
  where
    lastValue found [] = found
    lastValue _ (flag:value:rest) | flag == wanted = lastValue (Just value) rest
    lastValue found (flag:rest) = lastValue (case stripPrefix (wanted ++ "=") flag of
      Just value -> Just value; Nothing -> found) rest
