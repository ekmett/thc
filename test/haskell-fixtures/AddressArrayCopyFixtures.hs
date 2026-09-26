-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
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
      source = "compiler/test-fixtures/AddressArrayCopyAudit.hs"
      driver = "compiler/test-fixtures/AddressArrayCopyNative.hs"
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
    ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-icompiler/test-fixtures",
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
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    let core = directory </> stage ++ "-core/AddressArrayCopyAudit.json"
    reports <- forM entries $ \name -> do
      let reportPath = directory </> stage ++ "-" ++ name ++ "-audit.json"
      audited <- runLogged 120 root logs (stage ++ "-" ++ name ++ "-audit") [] "python3"
        ["scripts/audit-core.py",core,"--entry",name,"--output",reportPath]
      report <- BS.readFile (root </> reportPath) >>= either die pure . eitherDecodeStrict'
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
          KeyMap.lookup "issues" fields == Just (Array mempty),
          KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
        _ -> die ("Strict address/array copy audit rejected " ++ stage ++ "/" ++ name)
      pure (reportPath:commandArtifacts audited)
    pure (core:commandArtifacts exported ++ concat reports)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/AddressArrayCopyFixtures.hs",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py",
        "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [requestPath,directory </> "oracle.tsv",binary] ++ concat stages ++
        commandArtifacts compiled ++ commandArtifacts executed
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"ghcInfo" .= info,
    "entries" .= entries,"nativeRows" .= length rows,"bytesPerRow" .= (16 :: Int),
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("address-array-copy: " ++ show (length rows) ++ " native rows, sixteen exact bytes each; strict pre/post Core")
