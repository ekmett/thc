-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : MemsetFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for memset.
module MemsetFixtures (prepareMemset) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecodeStrict', object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

type Row = ((Int,Int,Int,[Int]),(Int,[Int]))

rowJSON :: Row -> Value
rowJSON ((offset,value,count,bytes),(returned,after)) = object
  ["offset" .= offset,"value" .= value,"count" .= count,"before" .= bytes,
   "returned" .= returned,"after" .= after]

prepareMemset :: FilePath -> IO ()
prepareMemset root = do
  let directory = "build/original-memset"
      source = "compiler/test-fixtures/OriginalMemsetAudit.hs"
      driver = "compiler/test-fixtures/OriginalMemsetNative.hs"
      binary = directory </> "native/oracle"
      entries = ["originalFill"] :: [String]
      execute = runLogged 180 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original memset requires GHC 9.14.1")
  compiled <- execute "native-build" [] ghc ["--make","-j2","-O2","-fforce-recomp",
    "-dcore-lint","-dstg-lint","-icompiler/test-fixtures",
    "-odir",root </> directory </> "native","-hidir",root </> directory </> "native",
    driver,"-o",root </> binary]
  observed <- execute "native-observations" [] (root </> binary) []
  rows <- maybe (die "Malformed native memset observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe [Row])
  unless (length rows == 198) (die "Unexpected native memset observation count")
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON (map rowJSON rows))
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "OriginalMemsetAudit.json",core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source])
    audits <- forM entries $ \entry -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py","--entry",entry,"--output",path] ++ modules)
      report <- either die pure . eitherDecodeStrict' =<< BS.readFile (root </> path)
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
        _ -> die "Original memset audit rejected"
      pure (path,command)
    pure (stage,modules,exported,audits)
  compilerFiles <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let commands = [version,compiled,observed] ++ concat [exported:map snd audits | (_,_,exported,audits) <- stages]
      inputs = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/MemsetFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json","compiler/build.sh","compiler/export.sh",
        "compiler/toolchain.sh","compiler/plugin.py","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["compiler/THC" </> path | path <- compilerFiles, takeExtension path == ".hs"] ++
        ["scripts" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = [binary,oracle] ++ concat [modules ++ map fst audits | (_,modules,_,audits) <- stages] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"entries" .= entries,"nativeRows" .= length rows,
     "strictAccepted" .= True,"runtimeVerified" .= False,
     "stages" .= Map.fromList [(stage,object ["modules" .= modules]) | (stage,modules,_,_) <- stages],
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands]
  putStrLn "original-memset: 198 native observations, original ByteString imports, two strict audits"
