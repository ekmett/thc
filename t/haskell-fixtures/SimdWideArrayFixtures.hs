-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (029 simd-wide-arrays)
-- Purpose: Check wide vector array storage and lane extraction.
-- Produces/consumed result: Pre CBD and scalar-lane native oracle.tsv.
-- Cost and overlap: Wide layouts need coverage beyond 128-bit cases. Reuse the vector-
--   memory pipeline; separate historical audit inventories add no value.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 029.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : SimdWideArrayFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for simd wide array.
module SimdWideArrayFixtures (prepareSimdWideArrays) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), FromJSON, fromJSON, Result(..), object, (.=), eitherDecodeStrict')
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import Data.String (fromString)
import FixtureSupport
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath

families :: [(String, Int, Int)]
families = [("int8",32,1),("word8",32,1),("int8",64,1),("word8",64,1),("int16",32,2),("word16",32,2),
  ("int16",16,2),("word16",16,2),("int32",8,4),("word32",8,4),("int32",16,4),("word32",16,4),("int64",4,8),("word64",4,8),("int64",8,8),("word64",8,8),("float",8,4),("float",16,4),("double",4,8),("double",8,8)]
entries :: [(String, Int, Int, Bool)]
entries = [(family ++ "X" ++ show lanes ++ operation ++ mode, width, lanes, mode == "Scalar") |
  (family,lanes,width) <- families, operation <- ["Index","Read","Write"], mode <- ["Packed","Scalar"]]
seeds :: [Integer]
seeds = [-2^(63::Int), -129, -1, 0, 1, 127, 65535, 2^(63::Int)-1]
requests :: [(String,Integer,Int)]
requests = [(name,seed,offset) | (name,_width,lanes,scalar) <- entries, seed <- seeds,
  offset <- if scalar then [0,1,2*lanes] else [0,1,2]]

field :: FromJSON a => Value -> String -> IO a
field (Object values) key = case KeyMap.lookup (fromString key) values of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> die message
  Nothing -> die ("Missing SIMD wide field: " ++ key)
field _ key = die ("Expected object for " ++ key)
readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'
prepareSimdWideArrays :: FilePath -> IO ()
prepareSimdWideArrays root = do
  let directory = "build/simd-wide-arrays"
      logs = directory </> "commands"
      manifest = root </> directory </> "manifest.json"
      source = "t/fixtures/compiler/SimdWideArrayAudit.hs"
      driver = "t/fixtures/compiler/SimdWideArrayNative.hs"
      inputs = directory </> "inputs.tsv"
      binary = directory </> "native/oracle"
      execute = runLogged 300 root logs
      stages = ["pre"]
  createDirectoryIfMissing True (root </> directory </> "native")
  exists <- doesFileExist manifest
  when exists (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "SIMD wide needs pinned GHC9.14.1")
  writeFile (root </> inputs) (unlines [name ++ "\t" ++ show seed ++ "\t" ++ show offset | (name,seed,offset) <- requests])
  nativeArtifacts <- do
    built <- execute "native-build" [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
      "-it/fixtures/compiler","-odir",directory </> "native","-hidir",directory </> "native",driver,"-o",binary]
    observed <- runLoggedWithInput inputs 120 root logs "native-oracle" [] (root </> binary) []
    rows <- maybe (die "Invalid native SIMD wide row") pure $ traverse (\row -> case splitTab row of
      [name,seed,offset,result,digest] -> do s <- readInteger seed; i <- readInteger offset; _ <- readInteger result; _ <- readInteger digest; pure (name,s,fromInteger i)
      _ -> Nothing) (lines (BSC.unpack (commandStdout observed)))
    unless (rows == requests && BS.null (commandStderr observed)) (die "Native SIMD wide domain/order changed")
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout observed)
    pure ([binary,directory </> "oracle.tsv"] ++ commandArtifacts built ++ commandArtifacts observed)
  exported <- forM stages $ \stage -> do
    let corePath = directory </> stage ++ "-core/SimdWideArrayAudit.cbd"
    compilation <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> directory </> stage ++ "-core"),("THC_GHC_OUT",root </> directory </> stage ++ "-ghc")]
      "bin/export-core.sh" (["-fno-code","-fwrite-if-simplified-core"] ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    reports <- forM entries $ \(name,_,_,_) -> do
      let path = directory </> stage ++ "-" ++ name ++ "-audit.json"
      command <- execute (stage ++ "-" ++ name ++ "-audit") [] "python3"
        ["bin/audit-core.py",corePath,"--entry","main:SimdWideArrayAudit." ++ name,"--output",path]
      report <- readJson (root </> path)
      accepted <- field report "accepted"
      missing <- field report "missingGlobals" :: IO [Value]
      issues <- field report "issues" :: IO [Value]
      unless (accepted && null missing && null issues) (die "Strict SIMD wide audit rejected")
      pure (name,object ["audit" .= path],path:commandArtifacts command)
    pure (stage,object ["core" .= corePath,"entries" .= Map.fromList [(name,record) | (name,record,_) <- reports]],
      corePath : commandArtifacts compilation ++ concat [paths | (_,_,paths) <- reports])
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root (sort $ [source,driver,"t/fixtures/compiler/SimdWideArrayScalar.hs","t/haskell-fixtures/SimdWideArrayFixtures.hs",
    "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Main.hs","thc.cabal","bin/export-core.sh",
    "bin/build-compiler.sh","bin/toolchain.sh","bin/plugin.py","bin/audit-core.py",
    "bin/core-capabilities.json","bin/simd-families.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> path | path <- plugin,takeExtension path == ".hs"] ++
    ["bin" </> path | path <- scripts,"core_" `isPrefixOf` path,takeExtension path == ".py"])
  artifactHashes <- hashes root (inputs : nativeArtifacts ++ commandArtifacts version ++ concat [paths | (_,_,paths) <- exported])
  writeJson manifest (object ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"entries" .= [name | (name,_,_,_) <- entries],
    "requests" .= length requests,"nativeRows" .= length requests, "nativeMode" .= ("scalar-lane" :: String),
    "stages" .= Map.fromList [(stage,record) | (stage,record,_) <- exported],
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes])
  putStrLn ("SIMD wide arrays: " ++ show (length requests) ++ " requests, stages " ++ show stages)
