-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalSigsetFixtures (prepareOriginalSigset) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import System.Info (os, arch)
import Text.Read (readMaybe)

directory :: FilePath
directory = "build/original-sigset"

fixtureSources :: FilePath -> IO [FilePath]
fixtureSources root = do
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  pure $ sort $ ["compiler/test-fixtures/OriginalSigsetAudit.hs", "compiler/test-fixtures/OriginalSigsetNative.hs",
    "thc.cabal", "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/OriginalSigsetFixtures.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
    "src/main/resources/thc/scalar-primop-signatures.json", "compiler/export.sh", "compiler/build.sh",
    "compiler/toolchain.sh", "compiler/plugin.py"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]

prepareOriginalSigset :: FilePath -> IO ()
prepareOriginalSigset root = do
  createDirectoryIfMissing True (root </> directory)
  let manifest = root </> directory </> "manifest.json"
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  if os /= "linux" || arch /= "x86_64" then do
    inputHashes <- fixtureSources root >>= hashes root
    writeJson manifest $ object ["schema" .= (1 :: Int), "platform" .= os, "supported" .= False,
      "reason" .= ("Original Linux x86_64 sigset declarations only" :: String),
      "inputHashes" .= inputHashes, "artifactHashes" .= object []]
    putStrLn "original-sigset: explicitly excluded on this platform"
  else do
    let entries = ["originalSigEmpty", "originalSigAdd"]
        execute = runLogged 180 root (directory </> "logs")
    createDirectoryIfMissing True (root </> directory </> "native")
    ghc <- maybe "ghc" id <$> lookupEnv "GHC"
    version <- execute "ghc-version" [] ghc ["--numeric-version"]
    unless (commandStdout version == "9.14.1\n") (die "Original sigset requires GHC 9.14.1")
    info <- execute "ghc-info" [] ghc ["--info"]
    case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
      Just target | lookup "Host platform" target == Just "x86_64-unknown-linux",
                    lookup "Target platform" target == lookup "Host platform" target,
                    lookup "target word size" target == Just "8" -> pure ()
      _ -> die "Original sigset requires native Linux x86_64 GHC"
    let binary = directory </> "native/oracle"
    compiled <- execute "native-build" [] ghc ["--make", "-j2", "-O2", "-fforce-recomp", "-dcore-lint",
      "-package", "ghc-internal", "-odir", root </> directory </> "native", "-hidir", root </> directory </> "native",
      "compiler/test-fixtures/OriginalSigsetNative.hs", "-o", root </> binary]
    observed <- execute "native-run" [] (root </> binary) []
    (size, rows) <- maybe (die "Malformed original sigset observations") pure
      (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe (Int, [(Int,Int,Int,Int,Int,[Int])]))
    let oracle = directory </> "oracle.json"
    writeJson (root </> oracle) $ object ["size" .= size, "rows" .= rows]
    exports <- forM ["pre","post"] $ \stage -> do
      let core = directory </> stage </> "core"
          modules = [core </> "OriginalSigsetAudit.json", core </> "THC.InterfaceClosure.json"]
          options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
            ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
      exported <- execute (stage ++ "-export")
        [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
        "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ ["compiler/test-fixtures/OriginalSigsetAudit.hs"])
      audits <- forM entries $ \entry -> do
        let path = directory </> stage </> entry ++ ".audit.json"
        command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
          (["scripts/audit-core.py", "--entry", entry, "--output", path] ++ modules)
        pure (path,command)
      pure (modules,exported,audits)
    let commands = [version,info,compiled,observed] ++ concat [exported : map snd audits | (_,exported,audits) <- exports]
        artifacts = [binary,oracle] ++ concat [modules ++ map fst audits | (modules,_,audits) <- exports] ++ concatMap commandArtifacts commands
    inputHashes <- fixtureSources root >>= hashes root
    artifactHashes <- hashes root artifacts
    writeJson manifest $ object ["schema" .= (1 :: Int), "platform" .= os, "supported" .= True,
      "oracle" .= oracle, "entries" .= entries, "strictAccepted" .= True, "runtimeVerified" .= False,
      "installedArtifactsHashed" .= False, "nativeRows" .= length rows, "inputHashes" .= inputHashes,
      "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
    putStrLn "original-sigset: native image/status/errno rows and four strict pre/post original audits prepared"
