-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module OriginalHandleReadinessFixtures (prepareOriginalHandleReadiness) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=), toJSON)
import qualified Data.ByteString.Char8 as BSC
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)

prepareOriginalHandleReadiness :: FilePath -> IO ()
prepareOriginalHandleReadiness root = do
  let directory = "build/original-handle-readiness"
      source = "compiler/test-fixtures/OriginalHandleReadinessAudit.hs"
      driver = "compiler/test-fixtures/OriginalHandleReadinessNative.hs"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original Handle readiness requires pinned GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | Just host <- lookup "Host platform" target,
                  not (null host), lookup "Target platform" target == Just host,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original Handle readiness requires a native 64-bit GHC"
  let binary = directory </> "native" </> "oracle"
  compiled <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint",
    "-package", "ghc-internal", "-package", "unix", "-odir", root </> directory </> "native",
    "-hidir", root </> directory </> "native", driver, "-o", root </> binary]
  rows <- forM [-1,1,2 :: Int] $ \fd -> do
    command <- execute ("native-" ++ show fd) [] (root </> binary) [show fd]
    let result = readMaybe (BSC.unpack (commandStdout command)) :: Maybe (Int,Int)
    (status,err) <- maybe (die ("Malformed native isatty result for " ++ show fd)) pure result
    unless (status == 0 && err > 0) (die ("Native isatty was not a nonterminal/invalid descriptor: " ++ show fd))
    pure (object ["fd" .= fd,"result" .= status,"errno" .= err], command)
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON [row | (row,_) <- rows])
  exports <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "OriginalHandleReadinessAudit.json",core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- ["originalIsTerminal", "originalIsTerminalErrno"]]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ [source])
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine GHC export: " ++ path))) modules
    audits <- forM ["originalIsTerminal", "originalIsTerminalErrno"] $ \entry -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py", "--entry", entry, "--output", path] ++ modules)
      pure (path,command)
    pure (modules, exported, audits)
  let sources = [source,driver,"thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/OriginalHandleReadinessFixtures.hs",
        "scripts/audit-core.py", "scripts/core_original_foreign.py", "scripts/core-capabilities.json",
        "compiler/export.sh", "compiler/build.sh", "compiler/THC/Plugin.hs"]
      commands = [version,info,compiled] ++ [command | (_,command) <- rows] ++
        concat [exported : [command | (_,command) <- audits] | (_,exported,audits) <- exports]
      artifacts = [binary,oracle] ++ concat [modules ++ [path | (path,_) <- audits] | (modules,_,audits) <- exports] ++
        concatMap commandArtifacts commands
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "nativeRows" .= (3 :: Int), "oracle" .= oracle,
     "stages" .= (["pre", "post"] :: [String]), "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-handle-readiness: native isatty/errno and strict pre/post Core prepared"
