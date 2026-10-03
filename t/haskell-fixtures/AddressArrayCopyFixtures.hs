-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (102 address-array-copy)
-- Purpose: Check copying pointer/address arrays preserves aliases and values.
-- Produces/consumed result: CBDs/oracle plus temporary synthetic CBDs/audits written by
--   the test.
-- Cost and overlap: Keep address transport/lifetime cases beyond ordinary byte copies.
--   Share memory setup and make the test-time encoder/auditor explicit rather than
--   assuming preparation built them.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 102.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : AddressArrayCopyFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for address array copy.
module AddressArrayCopyFixtures (prepareAddressArrayCopy) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (sort)
import FixtureSupport (CommandResult(..), hashes, readInteger, run, runLogged, runLoggedWithInput, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

entries :: [String]
entries = ["addrToArray","arrayToAddr","mutableArrayToAddr"]

requests :: [(String,Integer,Int,Int,Int)]
requests = [(name,seed,from,to,count) | name <- entries,
  seed <- [0,1,127,255,-1,2^(63 :: Int)-1,-2^(63 :: Int)],
  (from,to,count) <- [(a,b,n) | a <- [0..4], b <- [0..4], n <- [0..min (4-a) (4-b)]] ++
    [(0,0,8),(0,1,7),(1,0,7),(3,5,3),(5,3,3),(8,8,0),(8,0,0),(0,8,0)]]

prepareAddressArrayCopy :: FilePath -> IO ()
prepareAddressArrayCopy root = do
  let directory = "build/address-array-copy"
      output = root </> directory
      source = "t/fixtures/compiler/AddressArrayCopyAudit.hs"
      driver = "t/fixtures/compiler/AddressArrayCopyNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      logs = directory </> "commands"
      manifest = output </> "manifest.json"
      requestPath = directory </> "inputs.tsv"
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Address/array copies require pinned GHC 9.14.1")
  info <- run root [] ghc ["--info"] ""
  case readMaybe info :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8",
      Just host <- lookup "Host platform" fields, Just target <- lookup "Target platform" fields,
      host == target -> pure ()
    _ -> die "Address/array copy oracle requires native 64-bit GHC"
  writeFile (root </> requestPath) (unlines [name ++ "\t" ++ show seed ++ "\t" ++ show from ++ "\t" ++ show to ++ "\t" ++ show count |
    (name,seed,from,to,count) <- requests])
  compiled <- runLogged 300 root logs "native-build" [] ghc
    ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-it/fixtures/compiler",
     "-odir",root </> native,"-hidir",root </> native,driver,"-o",root </> binary]
  executed <- runLoggedWithInput requestPath 120 root logs "native-oracle" [] (root </> binary) []
  let parse line = case splitTab line of
        name:fields | length fields == 20 -> (,) name <$> traverse readInteger fields
        _ -> Nothing
  rows <- maybe (die "Malformed address/array copy oracle") pure (traverse parse (lines (BSC.unpack (commandStdout executed))))
  let expected (name,seed,from,to,count) = (name,[seed,toInteger from,toInteger to,toInteger count])
  unless (map (\(name,fields) -> (name,take 4 fields)) rows == map expected requests)
    (die "Changed address/array copy native input domain")
  unless (all (all (\byte -> byte >= 0 && byte <= 255) . drop 4 . snd) rows)
    (die "Invalid address/array copy byte observation")
  BS.writeFile (output </> "oracle.tsv") (commandStdout executed)
  stages <- forM ["pre","post"] $ \stage -> do
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",output </> stage ++ "-core"),("THC_GHC_OUT",output </> stage ++ "-ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    let core = directory </> stage ++ "-core/AddressArrayCopyAudit.cbd"
    reports <- forM entries $ \name -> do
      let reportPath = directory </> stage ++ "-" ++ name ++ "-audit.json"
      audited <- runLogged 120 root logs (stage ++ "-" ++ name ++ "-audit") [] "python3"
        ["bin/audit-core.py",core,"--entry","main:AddressArrayCopyAudit." ++ name,"--output",reportPath]
      report <- BS.readFile (root </> reportPath) >>= either die pure . eitherDecodeStrict'
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
          KeyMap.lookup "issues" fields == Just (Array mempty),
          KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
        _ -> die ("Strict address/array copy audit rejected " ++ stage ++ "/" ++ name)
      pure (reportPath:commandArtifacts audited)
    pure (core:commandArtifacts exported ++ concat reports)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let sources = sort $ [source,driver,"thc.cabal","t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/AddressArrayCopyFixtures.hs",
        "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py",
        "bin/audit-core.py","bin/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [requestPath,directory </> "oracle.tsv",binary] ++ concat stages ++
        commandArtifacts compiled ++ commandArtifacts executed
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"ghcInfo" .= info,
    "entries" .= entries,"nativeRows" .= length rows,"bytesPerRow" .= (16 :: Int),
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("address-array-copy: " ++ show (length rows) ++ " native rows, sixteen exact bytes each; strict pre/post Core")
