-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : BackendAnnotationFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1; source and interface publication
--
-- Real ANN controls share one source across pre-Tidy, late and interface export.
module BackendAnnotationFixtures (prepareBackendAnnotations) where

import Control.Monad (forM_, unless)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString.Char8 as BS
import Data.List (isInfixOf)
import Data.Foldable (toList)
import FixtureSupport
import GHC
import GHC.Plugins (liftIO, mainUnit, hsc_logger)
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv, getEnvironment)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>))
import System.Process (readCreateProcessWithExitCode, proc, cwd, env)
import THC.Interface (loadInterfaceCore, interfaceCoreCBD)
import THC.Compact.Module (readModuleMetadata, readModuleValue)

prepareBackendAnnotations :: FilePath -> IO ()
prepareBackendAnnotations root = do
  let directory = "build/backend-annotations"
      output = root </> directory
      source = "t/fixtures/compiler/BackendAnnotations.hs"
      logs = directory </> "commands"
      expected = object ["default" .= ("ast" :: String), "bindings" .= object
        ["main:BackendAnnotations.byteRoot" .= ("bytecode" :: String),
         "main:BackendAnnotations.value" .= ("bytecode" :: String),
         "main:BackendAnnotations.hidden" .= ("bytecode" :: String),
         "main:BackendAnnotations.closureByte" .= ("bytecode" :: String)]]
      check path = do
        bytes <- BS.readFile path
        (_,metadata) <- either die pure (readModuleMetadata bytes)
        unless (case metadata of Object fields -> KM.lookup "backendPolicy" fields == Just expected; _ -> False)
          (die ("Backend ANN policy missing or changed: " ++ path))
        model <- either die pure (readModuleValue bytes)
        let identities = case model of
              Object fields | Just (Array bindings) <- KM.lookup "bindings" fields ->
                [identity | Object binding <- toList bindings, Just identity <- [KM.lookup "id" binding]]
              _ -> []
        unless (all (\name -> String ("main:BackendAnnotations." <> name) `elem` identities)
          ["byteRoot","value","hidden","closureByte"])
          (die ("Backend ANN override does not match an emitted root: " ++ path))
  createDirectoryIfMissing True output
  forM_ ["pre","post"] $ \stage -> do
    _ <- runLogged 180 root logs (stage ++ "-export")
      [("THC_CORE_OUT",output </> stage),("THC_GHC_OUT",output </> stage ++ "-ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fwrite-if-simplified-core","-fexpose-all-unfoldings","-fno-worker-wrapper",source])
    check (output </> stage </> "BackendAnnotations.cbd")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  library <- runLogged 30 root logs "libdir" [] ghc ["--print-libdir"]
  libdir <- case lines (BS.unpack (commandStdout library)) of [path] -> pure path; _ -> die "Missing GHC libdir"
  bytes <- runGhc (Just libdir) $ do
    initial <- getSessionDynFlags
    initialEnv <- getSession
    (flags,leftovers,_) <- parseDynamicFlags (hsc_logger initialEnv) initial (map noLoc ["-dynamic"])
    unless (null leftovers) (liftIO (die "Unexpected ANN interface session options"))
    _ <- setSessionDynFlags flags
    environment <- getSession
    core <- liftIO (loadInterfaceCore environment (mkModule mainUnit (mkModuleName "BackendAnnotations"))
      (output </> "post-ghc/BackendAnnotations.hi")) >>= maybe (liftIO (die "Missing complete ANN interface Core")) pure
    liftIO (interfaceCoreCBD [] core)
  BS.writeFile (output </> "interface.cbd") bytes
  check (output </> "interface.cbd")
  let closure = output </> "closure"
      closureObjects = output </> "closure-ghc"
      use = output </> "BackendUse.hs"
      oneLine result = case lines (BS.unpack (commandStdout result)) of
        [line] -> pure line
        _ -> die "Expected one plugin configuration value"
  writeFile use "module BackendUse where\nimport BackendAnnotations\n{-# OPAQUE entry #-}\nentry :: Int -> Int\nentry x = closureAst x + closureByte x\n"
  createDirectoryIfMissing True closureObjects
  packageDb <- runLogged 30 root logs "plugin-db" [] "python3" ["bin/plugin.py","--field","packageDb"] >>= oneLine
  plugin <- runLogged 30 root logs "plugin-flag" [] "python3"
    ["bin/plugin.py","--external-plugin",closure,"-fplugin-opt=THC.Plugin:post-tidy","-fplugin-opt=THC.Plugin:closure=entry"] >>= oneLine
  _ <- runLogged 180 root logs "closure-export" [] ghc
    ["-c","-O2","-dynamic","-fforce-recomp","-dcore-lint","-package-db",packageDb,plugin,
     "-i","-i" ++ (output </> "post-ghc"),"-odir",closureObjects,"-hidir",output </> "post-ghc",use]
  (_,closureMetadata) <- BS.readFile (closure </> "THC.InterfaceClosure.cbd") >>= either die pure . readModuleMetadata
  let closureExpected = object ["bindings" .= object
        ["main:BackendAnnotations.closureAst" .= ("ast" :: String),
         "main:BackendAnnotations.closureByte" .= ("bytecode" :: String)]]
  unless (case closureMetadata of Object fields -> KM.lookup "backendPolicy" fields == Just closureExpected; _ -> False)
    (die "Interface closure lost original module/binding policies or leaked a module default")
  environment <- getEnvironment
  forM_ [("option","{-# ANN module (\"thc:backend=other\" :: String) #-}","Unsupported THC ANN option"),
         ("vectorize","{-# ANN module (\"thc:vectorize=on\" :: String) #-}","vectorization is JVM-global"),
         ("scope","{-# ANN type T (\"thc:backend=ast\" :: String) #-}","top-level function/value binder"),
         ("conflict","{-# ANN module (\"thc:backend=ast\" :: String) #-}\n{-# ANN module (\"thc:backend=bytecode\" :: String) #-}","Conflicting THC ANN")]
    $ \(label,annotation,message) -> do
      let path = output </> "Rejected.hs"
      writeFile path ("module Rejected where\n" ++ annotation ++ "\ndata T = T\n")
      (code,stdout,stderr) <- readCreateProcessWithExitCode ((proc "bin/export-core.sh"
        ["-fplugin-opt=THC.Plugin:post-tidy",path]) {cwd=Just root,
          env=Just (("THC_CORE_OUT",output </> "rejected"):("THC_GHC_OUT",output </> "rejected-ghc"):
            filter (\(key,_) -> key /= "THC_CORE_OUT" && key /= "THC_GHC_OUT") environment)}) ""
      writeFile (output </> label ++ ".log") (stdout ++ stderr)
      unless (code /= ExitSuccess && message `isInfixOf` (stdout ++ stderr))
        (die ("Expected backend ANN rejection: " ++ label))
  putStrLn "Backend ANN: pre-Tidy, post-Tidy and installed-interface policies agree; imported closure policies retained; four invalid controls rejected"
