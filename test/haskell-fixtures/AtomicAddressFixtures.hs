-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module AtomicAddressFixtures (prepareAtomicAddress) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (sort)
import FixtureSupport
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

inputs :: [String]
inputs =
  [unwords ["numeric", show op, show x, show y, show z] |
    op <- [0..13 :: Int],
    (x,y,z) <- [(0,0,1), (1,1,0), (2^(64 :: Int)-1,2^(64 :: Int)-1,0),
      (2^(63 :: Int),2^(63 :: Int),2^(64 :: Int)-1), (0x123456789abcdef0,0x123456789abcdef0,0xfedcba9876543210),
      (0x123456789abcdef0,0xaaaaaaaaaaaaaaaa,0x5555555555555555),
      (0xffffffffffff00ff,0x100ff,0x123456789abcdef0)] :: [(Integer,Integer,Integer)]] ++
  [unwords ["pointer", show op, show x, show y, show z] |
    op <- [0,1 :: Int], (x,y,z) <- [(16,16,32),(16,24,32),(16,16,-1),(-1,-1,48),(-1,16,48)] :: [(Int,Int,Int)]]

prepareAtomicAddress :: FilePath -> IO ()
prepareAtomicAddress root = do
  let directory = "build/atomic-address"
      output = root </> directory
      logs = directory </> "logs"
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/AtomicAddressAudit.hs"
      driver = "compiler/test-fixtures/AtomicAddressNative.hs"
      entries = ["atomicAddressNumeric","atomicAddressPointer","atomicAddressNumericAt","atomicAddressPointerAt"] :: [String]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "version" [] ghc ["--numeric-version"]
  unless (BSC.lines (commandStdout version) == ["9.14.1"]) (die "Atomic Addr requires GHC 9.14.1")
  stageArtifacts <- fmap concat $ forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    exported <- runLogged 300 root logs ("export-" ++ stage)
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source])
    audited <- runLogged 120 root logs ("audit-" ++ stage) [] "python3"
      (["scripts/audit-core.py"] ++ concatMap (\entry -> ["--entry",entry]) entries ++
       ["--output",directory </> stage </> "audit.json",core </> "AtomicAddressAudit.json"])
    pure (commandArtifacts exported ++ commandArtifacts audited ++
      [core </> "AtomicAddressAudit.json", directory </> stage </> "audit.json"])
  let native = output </> "native"
  createDirectoryIfMissing True native
  built <- runLogged 180 root logs "native-build" [] ghc
    ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint","-i" ++ root </> "compiler/test-fixtures",
     "-odir",native,"-hidir",native,root </> driver,"-o",native </> "oracle"]
  writeFile (output </> "inputs.txt") (unlines inputs)
  oracle <- runLoggedWithInput (directory </> "inputs.txt") 60 root logs "native-oracle" []
    (native </> "oracle") []
  let rows = map (splitTab . BSC.unpack) (BSC.lines (commandStdout oracle))
  unless (length rows == length inputs && and (zipWith (\input row -> case row of
      kind:_ -> take 5 row == words input && length row == (if kind == "numeric" then 9 else 7)
      [] -> False)
      inputs rows)) (die "Incomplete or malformed native atomic Addr corpus")
  BS.writeFile (output </> "oracle.tsv") (commandStdout oracle)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  sourceHashes <- hashes root $ sort $
    [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
     "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/AtomicAddressFixtures.hs",
     "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json","compiler/build.sh",
     "compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
    ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
  artifactHashes <- hashes root (stageArtifacts ++ commandArtifacts version ++
    commandArtifacts built ++ commandArtifacts oracle ++ [directory </> "inputs.txt",directory </> "oracle.tsv"])
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length rows,"entries" .= entries,"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes]
  putStrLn ("atomic-address: " ++ show (length rows) ++ " native rows, 16 primops, strict pre/post Core")
