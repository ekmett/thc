-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Cabal invokes this transparent compiler for ordinary owned native builds
-- and private store builds.
-- The native command is unchanged; a second invocation exports Core while
-- Cabal's unpacked source and generated files still exist.
module THC.Driver.GhcProxy (runGhcProxy) where

import Control.Monad (unless, when)
import System.Directory (createDirectoryIfMissing)
import GHC.ResponseFile (expandResponse)
import THC.Driver.NativeRecipe (captureNativeRecipe)
import System.Environment (getEnv, lookupEnv)
import System.Exit (ExitCode(..), exitWith)
import System.FilePath ((</>))
import System.Process (rawSystem)

runGhcProxy :: [String] -> IO ()
runGhcProxy arguments = do
  compiler <- getEnv "THC_PROXY_GHC"
  options <- expandResponse arguments
  native <- rawSystem compiler arguments
  when (native /= ExitSuccess) (exitWith native)
  receipts <- lookupEnv "THC_PROXY_NATIVE_RECIPES"
  mapM_ (\directory -> captureNativeRecipe directory compiler options) receipts
  targetUnits <- maybe [] lines <$> lookupEnv "THC_PROXY_GLOBAL_UNITS"
  case ("--make" `elem` options, valueAfter "-this-unit-id" options) of
    (True, Just unit) | unit `elem` targetUnits -> do
      capture <- getEnv "THC_PROXY_CAPTURE"
      pluginDb <- getEnv "THC_PROXY_PLUGIN_DB"
      pluginUnit <- getEnv "THC_PROXY_PLUGIN_UNIT"
      let root = capture </> unit
          core = root </> "core"
          objects = root </> "objects"
      createDirectoryIfMissing True core
      createDirectoryIfMissing True objects
      exported <- rawSystem compiler (arguments ++
        ["-no-link", "-fforce-recomp", "-outputdir", objects, "-odir", objects,
         "-hidir", objects, "-hiedir", objects </> "hie", "-stubdir", objects,
         "-package-db", pluginDb, "-plugin-package-id", pluginUnit,
         "-fplugin=THC.Plugin", "-fplugin-trustworthy",
         "-fplugin-opt=THC.Plugin:" ++ core,
         "-fplugin-opt=THC.Plugin:post-tidy", "-fplugin-opt=THC.Plugin:unit-qualified",
         "-fplugin-opt=THC.Plugin:source-notes",
         "-fplugin-opt=THC.Plugin:foreign-import-provenance", "-g", "-dcore-lint"])
      unless (exported == ExitSuccess) (exitWith exported)
    _ -> pure ()

valueAfter :: Eq a => a -> [a] -> Maybe a
valueAfter wanted values = case dropWhile (/= wanted) values of
  (_:value:_) -> Just value
  _ -> Nothing
