-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module ManagedAddressReadFixtures (prepareManagedAddressReads) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.Bits (finiteBitSize)
import Data.List (intercalate, isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (hashes, readInteger, run, runWithTimeout, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

entries :: [(String,String,Int)]
entries = [("word32Read","readWord32OffAddr#",4),
           ("wordRead","readWordOffAddr#",8),
           ("int32Read","readInt32OffAddr#",4),
           ("intRead","readIntOffAddr#",8)]

seeds :: [Integer]
seeds = [-2^(63 :: Int),-4294967296,-2147483649,-2147483648,-1,0,1,127,
         128,255,256,2147483647,2147483648,4294967295,2^(63 :: Int)-1]

requests :: [[String]]
requests = [[name,show raw,show base,show ((start-base) `div` width)]
  | (name,_,width) <- entries, raw <- seeds, base <- [0,8..32],
    start <- [0,width..32-width]]

prepareManagedAddressReads :: FilePath -> IO ()
prepareManagedAddressReads root = do
  let directory = "build/managed-address-reads"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/ManagedAddressReadAudit.hs"
      driver = "compiler/test-fixtures/ManagedAddressReadNative.hs"
      native = directory </> "native"
      binary = native </> "managed-address-read-oracle"
      requestText = unlines (map (intercalate "\t") requests)
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"] && finiteBitSize (0 :: Int) == 64)
    (die "Managed address reads require native 64-bit GHC 9.14.1")
  writeFile (output </> "requests.tsv") requestText
  createDirectoryIfMissing True (root </> native)
  _ <- run root [] ghc ["--make","-O2","-j2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-icompiler/test-fixtures","-odir",native,"-hidir",native,driver,"-o",binary] ""
  observed <- runWithTimeout (Just (60 * 1000000)) root [] (root </> binary) [] requestText
  let validRow (request,row) = case splitTab row of
        [name,raw,base,offset,result] ->
          [name,raw,base,offset] == request && readInteger result /= Nothing
        _ -> False
  unless (length requests == 1800 && length (lines observed) == length requests &&
          all validRow (zip requests (lines observed)))
    (die "Malformed native managed-address read oracle")
  writeFile (output </> "oracle.tsv") observed
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> (stage ++ "-core")
        ghcOut = directory </> (stage ++ "-ghc")
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ name | (name,_,_) <- entries]
    _ <- run root [("THC_CORE_OUT",root </> core), ("THC_GHC_OUT",root </> ghcOut)]
      "compiler/export.sh" (options ++ [source]) ""
    files <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless ("ManagedAddressReadAudit.json" `elem` files)
      (die ("Missing " ++ stage ++ " managed-address Core"))
    forM_ entries $ \(name,_,_) -> do
      let report = directory </> (stage ++ "-" ++ name ++ ".audit.json")
      _ <- run root [] "python3" ["scripts/audit-core.py", "--entry",name,
        "--output",report,core] ""
      pure ()
    pure (stage,map (core </>) files)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","cabal.project",
        "test/haskell-fixtures/Main.hs","test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/ManagedAddressReadFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "compiler/export.sh","compiler/build.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, "core_" `isPrefixOf` file, takeExtension file == ".py"]
      artifacts = [binary,directory </> "oracle.tsv",directory </> "requests.tsv"] ++
        concat [files ++ [directory </> (stage ++ "-" ++ name ++ ".audit.json")
                        | (name,_,_) <- entries] | (stage,files) <- stages]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "wordBits" .= (64 :: Int),"entries" .= [name | (name,_,_) <- entries],
    "nativeRows" .= length requests,"strictAccepted" .= True,
    "strictAudits" .= (8 :: Int),"stages" .= Map.fromList stages,
    "inputHashes" .= sourceHashes,"artifactHashes" .= artifactHashes]
  putStrLn "managed-address-reads: 1800 native rows, eight strict pre/post Core audits"
