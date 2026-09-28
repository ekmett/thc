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
-- Only a selected THC-only runnable unit omits its native final link. A second
-- invocation exports Core while
-- Cabal's unpacked source and generated files still exist.
module THC.Driver.GhcProxy (runGhcProxy, ghcProxyCommand, ghcProxyWindowsCommand, directPlugin) where

import Control.Monad (unless, when)
import Data.List (stripPrefix)
import System.Directory (createDirectoryIfMissing)
import GHC.ResponseFile (expandResponse)
import THC.Driver.NativeRecipe (captureNativeRecipe)
import THC.Driver.PackageNative (captureNativeObject, captureNativeComponent, capturePackageNative)
import System.Environment (getEnv, lookupEnv)
import System.Exit (ExitCode(..), exitWith)
import System.FilePath ((</>))
import System.Process (rawSystem)

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
  noLinkUnit <- lookupEnv "THC_PROXY_NO_LINK_UNIT"
  let noLink = ["-no-link" | "--make" `elem` options, Just unit <- [valueAfter "-this-unit-id" options],
                            not (null unit), noLinkUnit == Just unit]
      nativeOptions = options ++ noLink
  native <- rawSystem compiler (arguments ++ noLink)
  when (native /= ExitSuccess) (exitWith native)
  receipts <- lookupEnv "THC_PROXY_NATIVE_RECIPES"
  mapM_ (\directory -> captureNativeRecipe directory compiler nativeOptions) receipts
  nativePieces <- lookupEnv "THC_PROXY_NATIVE_PIECES"
  mapM_ (\directory -> captureNativeComponent directory nativeOptions) nativePieces
  mapM_ (\directory -> captureNativeObject directory compiler nativeOptions) nativePieces
  targetUnits <- maybe [] lines <$> lookupEnv "THC_PROXY_GLOBAL_UNITS"
  case ("--make" `elem` options, valueAfter "-this-unit-id" options) of
    (True, Just unit) | unit `elem` targetUnits -> do
      capture <- getEnv "THC_PROXY_CAPTURE"
      pluginDb <- getEnv "THC_PROXY_PLUGIN_DB"
      pluginUnit <- getEnv "THC_PROXY_PLUGIN_UNIT"
      pluginLibrary <- getEnv "THC_PROXY_PLUGIN_LIBRARY"
      let root = capture </> unit
          core = root </> "core"
          objects = root </> "objects"
      createDirectoryIfMissing True core
      createDirectoryIfMissing True objects
      exported <- rawSystem compiler (arguments ++
        ["-no-link", "-fforce-recomp", "-outputdir", objects, "-odir", objects,
         "-hidir", objects, "-hiedir", objects </> "hie", "-stubdir", objects,
         "-package-db", pluginDb, "-fplugin-trustworthy",
         directPlugin pluginLibrary pluginUnit
           [core, "post-tidy", "unit-qualified", "source-notes", "foreign-import-provenance"] options,
         -- Adding -g only to the replay can change CSE/tidied helper names.
         -- Consumers use the native interfaces, so preserve their debug flags.
         "-fwrite-if-simplified-core",
         "-dcore-lint"])
      unless (exported == ExitSuccess) (exitWith exported)
      helper <- lookupEnv "THC_PROXY_INTERFACE_HELPER"
      libdir <- lookupEnv "THC_PROXY_INTERFACE_LIBDIR"
      case (helper,libdir) of
        (Just executable,Just selectedLibdir) ->
          getEnv "THC_PROXY_ROOT" >>= \repository ->
            capturePackageNative repository executable selectedLibdir compiler options unit root
        _ -> pure ()
    _ -> pure ()

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
