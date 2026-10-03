-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (028 simd128-arrays)
-- Purpose: Check 128-bit integer vector array loads/stores across element widths.
-- Produces/consumed result: CBDs, input cases and native/model oracle rows.
-- Cost and overlap: Keep width/lane memory cases, sharing setup with the broader vector-
--   memory corpus. ARM model-only coverage must not be described as native verification.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 028.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Simd128ArrayFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for simd128 array.
module Simd128ArrayFixtures (prepareSimd128Arrays) where

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
import System.Info (arch)

families :: [(String, Int, Int)]
families = [("int8",16,1),("word8",16,1),("int16",8,2),("word16",8,2),("int64",2,8),("word64",2,8)]
entries :: [(String, Int, Bool)]
entries = [(family ++ "X" ++ show lanes ++ operation ++ mode, width, mode == "Scalar") |
  (family,lanes,width) <- families, operation <- ["Index","Read","Write"], mode <- ["Packed","Scalar"]]
seeds :: [Integer]
seeds = [-2^(63::Int), -2^(32::Int)-1, -65537, -32769, -129, -1, 0, 1, 127, 128, 255, 256, 32767, 65535, 2^(32::Int)+1, 2^(63::Int)-1]
requests :: [(String,Integer,Int)]
requests = [(name,seed,offset) | (name,width,scalar) <- entries, seed <- seeds,
  offset <- if scalar then [0,1,2,32 `div` width-1,32 `div` width] else [0,1,2]]

field :: FromJSON a => Value -> String -> IO a
field (Object values) key = case KeyMap.lookup (fromString key) values of
  Just value -> case fromJSON value of Success result -> pure result; Error message -> die message
  Nothing -> die ("Missing SIMD128 field: " ++ key)
field _ key = die ("Expected object for " ++ key)
readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'
prepareSimd128Arrays :: FilePath -> IO ()
prepareSimd128Arrays root = do
  let directory = "build/simd128-arrays"
      logs = directory </> "commands"
      manifest = root </> directory </> "manifest.json"
      source = "t/fixtures/compiler/Simd128ArrayAudit.hs"
      driver = "t/fixtures/compiler/Simd128ArrayNative.hs"
      inputs = directory </> "inputs.tsv"
      binary = directory </> "native/oracle"
      execute = runLogged 300 root logs
      exportedOnly = arch `elem` ["aarch64","arm64"]
      stages = if exportedOnly then ["pre"] else ["pre","post"]
  createDirectoryIfMissing True (root </> directory </> "native")
  exists <- doesFileExist manifest
  when exists (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "SIMD128 needs pinned GHC9.14.1")
  writeFile (root </> inputs) (unlines [name ++ "\t" ++ show seed ++ "\t" ++ show offset | (name,seed,offset) <- requests])
  nativeArtifacts <- if exportedOnly then pure [] else do
    built <- execute "native-build" [] ghc ["--make","-O2","-fllvm","-fforce-recomp","-dcore-lint","-dstg-lint",
      "-it/fixtures/compiler","-odir",directory </> "native","-hidir",directory </> "native",driver,"-o",binary]
    observed <- runLoggedWithInput inputs 120 root logs "native-oracle" [] (root </> binary) []
    rows <- maybe (die "Invalid native SIMD128 row") pure $ traverse (\row -> case splitTab row of
      [name,seed,offset,result] -> do s <- readInteger seed; i <- readInteger offset; _ <- readInteger result; pure (name,s,fromInteger i)
      _ -> Nothing) (lines (BSC.unpack (commandStdout observed)))
    unless (rows == requests && BS.null (commandStderr observed)) (die "Native SIMD128 domain/order changed")
    BS.writeFile (root </> directory </> "oracle.tsv") (commandStdout observed)
    pure ([binary,directory </> "oracle.tsv"] ++ commandArtifacts built ++ commandArtifacts observed)
  exported <- forM stages $ \stage -> do
    let corePath = directory </> stage ++ "-core/Simd128ArrayAudit.cbd"
    compilation <- execute (stage ++ "-export")
      [("THC_CORE_OUT",root </> directory </> stage ++ "-core"),("THC_GHC_OUT",root </> directory </> stage ++ "-ghc")]
      "bin/export-core.sh" ((if exportedOnly then ["-fno-code","-fwrite-if-simplified-core"] else ["-fllvm"]) ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    reports <- forM entries $ \(name,_,_) -> do
      let path = directory </> stage ++ "-" ++ name ++ "-audit.json"
      command <- execute (stage ++ "-" ++ name ++ "-audit") [] "python3"
        ["bin/audit-core.py",corePath,"--entry","main:Simd128ArrayAudit." ++ name,"--output",path]
      report <- readJson (root </> path)
      accepted <- field report "accepted"
      missing <- field report "missingGlobals" :: IO [Value]
      issues <- field report "issues" :: IO [Value]
      unless (accepted && null missing && null issues) (die "Strict SIMD128 audit rejected")
      pure (name,object ["audit" .= path],path:commandArtifacts command)
    pure (stage,object ["core" .= corePath,"entries" .= Map.fromList [(name,record) | (name,record,_) <- reports]],
      corePath : commandArtifacts compilation ++ concat [paths | (_,_,paths) <- reports])
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root (sort $ [source,driver,"t/haskell-fixtures/Simd128ArrayFixtures.hs",
    "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Main.hs","thc.cabal","bin/export-core.sh",
    "bin/build-compiler.sh","bin/toolchain.sh","bin/plugin.py","bin/audit-core.py",
    "bin/core-capabilities.json","bin/simd-families.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> path | path <- plugin,takeExtension path == ".hs"] ++
    ["bin" </> path | path <- scripts,"core_" `isPrefixOf` path,takeExtension path == ".py"])
  artifactHashes <- hashes root (inputs : nativeArtifacts ++ commandArtifacts version ++ concat [paths | (_,_,paths) <- exported])
  writeJson manifest (object ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),"entries" .= [name | (name,_,_) <- entries],
    "requests" .= length requests,"nativeRows" .= (if exportedOnly then Nothing else Just (length requests)),
    "stages" .= Map.fromList [(stage,record) | (stage,record,_) <- exported],
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes])
  putStrLn ("SIMD128 arrays: " ++ show (length requests) ++ " requests, stages " ++ show stages)
