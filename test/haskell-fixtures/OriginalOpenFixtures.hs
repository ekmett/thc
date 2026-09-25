-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module OriginalOpenFixtures (prepareOriginalOpen) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, toJSON, (.=))
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile, removePathForcibly)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import System.Info (os, arch)
import Text.Read (readMaybe)

prepareOriginalOpen :: FilePath -> IO ()
prepareOriginalOpen root = do
  let directory = "build/original-open"
      source = "compiler/test-fixtures/OriginalOpenAudit.hs"
      driver = "compiler/test-fixtures/OriginalOpenNative.hs"
      execute = runLogged 180 root (directory </> "logs")
      binary = directory </> "native/oracle"
      manifest = root </> directory </> "manifest.json"
  createDirectoryIfMissing True (root </> directory </> "native")
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  if os /= "linux" || arch /= "x86_64" then do
    writeJson manifest $ object ["schema" .= (1 :: Int), "supported" .= False,
      "reason" .= ("Original raw open is currently Linux x86_64 only" :: String)]
    else do
      ghc <- maybe "ghc" id <$> lookupEnv "GHC"
      version <- execute "ghc-version" [] ghc ["--numeric-version"]
      unless (commandStdout version == "9.14.1\n") (die "Original open requires GHC 9.14.1")
      info <- execute "ghc-info" [] ghc ["--info"]
      case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
        Just target | Just host <- lookup "Host platform" target, not (null host),
                      lookup "Target platform" target == Just host, lookup "target word size" target == Just "8" -> pure ()
        _ -> die "Original open requires native 64-bit GHC"
      compiled <- execute "native-build" [] ghc ["--make", "-j2", "-O2", "-fforce-recomp", "-dcore-lint",
        "-package", "ghc-internal", "-package", "unix", "-icompiler/test-fixtures",
        "-odir", root </> directory </> "native", "-hidir", root </> directory </> "native", driver, "-o", root </> binary]
      -- One explicitly fixture-owned scratch directory; not a cache artifact.
      let scratch = root </> directory </> "native/cases"
      removePathForcibly scratch
      observed <- execute "native-run" [] (root </> binary) [scratch]
      rows <- maybe (die "Malformed original-open native observations") pure
        (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe [(String,[Int],Bool,Int,Integer,Int,Int,[Int],[Int],Bool)])
      let oracle = directory </> "oracle.json"
      writeJson (root </> oracle) (toJSON rows)
      exports <- forM ["pre","post"] $ \stage -> do
        let core = directory </> stage </> "core"
            modules = [core </> "OriginalOpenAudit.json", core </> "THC.InterfaceClosure.json"]
            entries = ["originalOpen", "originalOpenSafe", "originalOpenInterruptible"]
            options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
              ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
        exported <- execute (stage ++ "-export")
          [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
          "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ [source])
        audits <- forM entries $ \entry -> do
          let output = directory </> stage </> entry ++ ".audit.json"
          command <- runLoggedExpect (if entry == "originalOpen" then 0 else 1) 180 root (directory </> "logs")
            (stage ++ "-audit-" ++ entry) [] "python3"
            (["scripts/audit-core.py", "--entry", entry, "--output", output] ++ modules)
          pure (output,command)
        pure (modules,exported,audits)
      plugin <- listDirectory (root </> "compiler/THC")
      scripts <- listDirectory (root </> "scripts")
      inputHashes <- hashes root $ sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/OriginalOpenFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      let commands = [version,info,compiled,observed] ++ concat [exported : map snd audits | (_,exported,audits) <- exports]
          artifacts = [binary,oracle] ++ concat [modules ++ map fst audits | (modules,_,audits) <- exports] ++ concatMap commandArtifacts commands
      artifactHashes <- hashes root artifacts
      writeJson manifest $ object ["schema" .= (1 :: Int),"supported" .= True,"strictAccepted" .= True,
        "runtimeVerified" .= False,"installedArtifactsHashed" .= False,"nativeRows" .= length rows,
        "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands]
      putStrLn "original-open: native raw-byte observations; unsafe accepted, safe/interruptible rejected"
