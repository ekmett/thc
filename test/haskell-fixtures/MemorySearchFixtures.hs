-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : MemorySearchFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for memory search.
module MemorySearchFixtures (prepareMemorySearch) where

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

type Row = ((String,[Int],Int,[Int],Int,Int,Int),Int)

rowJSON :: Row -> Value
rowJSON ((entry,left,leftOffset,right,rightOffset,needle,count),result) = object
  ["entry" .= entry,"left" .= left,"leftOffset" .= leftOffset,"right" .= right,
   "rightOffset" .= rightOffset,"needle" .= needle,"count" .= count,"result" .= result]

prepareMemorySearch :: FilePath -> IO ()
prepareMemorySearch root = do
  let directory = "build/original-memory-search"
      source = "test/fixtures/compiler/OriginalMemorySearchAudit.hs"
      driver = "test/fixtures/compiler/OriginalMemorySearchNative.hs"
      binary = directory </> "native/oracle"
      entries = ["originalCompare","originalFind"] :: [String]
      execute = runLogged 180 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original memory search requires GHC 9.14.1")
  compiled <- execute "native-build" [] ghc ["--make","-j2","-O2","-fforce-recomp",
    "-dcore-lint","-dstg-lint","-itest/fixtures/compiler",
    "-odir",root </> directory </> "native","-hidir",root </> directory </> "native",
    driver,"-o",root </> binary]
  observed <- execute "native-observations" [] (root </> binary) []
  rows <- maybe (die "Malformed native memory search observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe [Row])
  unless (length rows == 392) (die "Unexpected native memory search observation count")
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON (map rowJSON rows))
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "OriginalMemorySearchAudit.json",core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> directory </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source])
    audits <- forM entries $ \entry -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["bin/audit-core.py","--entry",entry,"--output",path] ++ modules)
      report <- either die pure . eitherDecodeStrict' =<< BS.readFile (root </> path)
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
        _ -> die "Original memory search audit rejected"
      pure (path,command)
    pure (stage,modules,exported,audits)
  compilerFiles <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let commands = [version,compiled,observed] ++ concat [exported:map snd audits | (_,_,exported,audits) <- stages]
      inputs = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/MemorySearchFixtures.hs",
        "bin/audit-core.py","bin/core-capabilities.json","bin/build-compiler.sh","bin/export-core.sh",
        "bin/toolchain.sh","bin/plugin.py","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["src/compiler/THC" </> path | path <- compilerFiles, takeExtension path == ".hs"] ++
        ["bin" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = [binary,oracle] ++ concat [modules ++ map fst audits | (_,modules,_,audits) <- stages] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"entries" .= entries,"nativeRows" .= length rows,
     "strictAccepted" .= True,"runtimeVerified" .= False,
     "stages" .= Map.fromList [(stage,object ["modules" .= modules]) | (stage,modules,_,_) <- stages],
     "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes,"commands" .= map commandRecord commands]
  putStrLn "original-memory-search: 392 native observations, original ByteString imports, four strict audits"
