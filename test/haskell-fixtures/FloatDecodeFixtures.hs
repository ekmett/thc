-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module FloatDecodeFixtures (prepareFloatDecode) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.|.), shiftL)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Set as Set
import FixtureSupport (CommandResult(..), hashes, readInteger, run, runLogged, runLoggedWithInput, splitTab, writeJson)
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

entries :: [String]
entries = [family ++ suffix | family <- ["float","double"], suffix <- ["Direct","Call","Exponent"]] ++
  ["floatExampleExponent","doubleExampleExponent"]

vendorSources :: [FilePath]
vendorSources = ["vendor/ghc-9.14.1/GHC/Internal/Bignum" </> name ++ suffix |
  name <- ["BigNat","Integer","Natural"], suffix <- [".hs",".hs-boot"]] ++
  ["vendor/ghc-9.14.1/include/WordSize.h","vendor/ghc-9.14.1/LICENSE"]

-- Every exponent code, every leading subnormal bit, both signs, boundary
-- fractions and deterministic integer-generated random encodings. No host FP.
inputs :: Int -> [Integer]
inputs width = Set.toAscList . Set.fromList . map signed $
  [sign .|. bits | sign <- [0,2^(width-1)], bits <- magnitudes]
  where
    p = if width == 32 then 23 else 52 :: Int
    exponentBits = if width == 32 then 8 else 11 :: Int
    bias = 2^(exponentBits-1)-1
    top = 2^exponentBits-1
    magnitudes = [e `shiftL` p | e <- [0..top]] ++
      [e `shiftL` p .|. f | e <- [0,1,bias-1,bias,bias+1,top-1,top], f <- [1,2^p-1,2^(p-1)]] ++
      [2^bit + delta | bit <- [0..p-1], delta <- [-1,0,1]] ++
      take 128 (map (`mod` 2^(width-1)) (drop 1 (iterate step 0xdec0de)))
    step x = (x * 6364136223846793005 + 1442695040888963407) `mod` 2^(64 :: Int)
    signed x = if x >= 2^(63 :: Int) then x-2^(64 :: Int) else x

prepareFloatDecode :: FilePath -> IO ()
prepareFloatDecode root = do
  let directory = "build/float-decode"
      output = root </> directory
      source = "compiler/test-fixtures/FloatDecodeAudit.hs"
      example = "examples/THC/FloatDecode.hs"
      driver = "compiler/test-fixtures/FloatDecodeNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      logs = directory </> "commands"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Floating decode requires pinned GHC 9.14.1")
  info <- run root [] ghc ["--info"] ""
  case readMaybe info :: Maybe [(String,String)] of
    Just fields | lookup "target word size" fields == Just "8",
      Just host <- lookup "Host platform" fields, Just target <- lookup "Target platform" fields,
      host == target -> pure ()
    _ -> die "Floating decode fixtures require native 64-bit GHC"
  let requests = [(name,bits) | name <- entries, bits <- inputs (if "float" `isPrefixOf` name then 32 else 64)]
      requestPath = directory </> "inputs.tsv"
  writeFile (root </> requestPath) (unlines [name ++ "\t" ++ show bits | (name,bits) <- requests])
  compiled <- runLogged 300 root logs "native-build" [] ghc
    ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint","-icompiler/test-fixtures","-iexamples",
     "-odir",root </> native,"-hidir",root </> native,driver,"-o",root </> binary]
  executed <- runLoggedWithInput requestPath 120 root logs "native-oracle" [] (root </> binary) []
  let parse line = case splitTab line of
        [name,bits,m,e] -> (,,,) name <$> readInteger bits <*> readInteger m <*> readInteger e
        _ -> Nothing
  rows <- maybe (die "Malformed floating decode oracle") pure
    (traverse parse (lines (BSC.unpack (commandStdout executed))))
  unless ([(name,bits) | (name,bits,_,_) <- rows] == requests) (die "Changed native decode corpus")
  BS.writeFile (output </> "oracle.tsv") (commandStdout executed)
  -- Public Double.exponent calls GHC's opaque integerFromInt64# worker.
  -- Retain the complete unchanged original module; never synthesize its body.
  -- The private interface overlay is build scratch, not a fixture-cache input.
  let boot = "build/float-decode-originals"
      original = directory </> "original/GHC.Internal.Bignum.Integer.json"
      provenance = directory </> "original/boot-provenance.json"
  bootExport <- runLogged 300 root logs "boot-export" [] "python3"
    ["compiler/export-boot.py","--frontier","bignum","--build-dir",boot]
  createDirectoryIfMissing True (output </> "original")
  copyFile (root </> boot </> "core/GHC.Internal.Bignum.Integer.json") (root </> original)
  copyFile (root </> boot </> "boot-provenance.json") (root </> provenance)
  stages <- forM ["pre","post"] $ \stage -> do
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT",output </> stage ++ "-core"),("THC_GHC_OUT",output </> stage ++ "-ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source,example])
    let corePaths = [directory </> stage ++ "-core" </> name ++ ".json" | name <- ["FloatDecodeAudit","THC.FloatDecode"]]
    reports <- forM entries $ \name -> do
      let reportPath = directory </> stage ++ "-" ++ name ++ "-audit.json"
      audited <- runLogged 120 root logs (stage ++ "-" ++ name ++ "-audit") [] "python3"
        (["scripts/audit-core.py"] ++ corePaths ++ [original,"--entry",name,"--output",reportPath])
      report <- BS.readFile (root </> reportPath) >>= either die pure . eitherDecodeStrict'
      case report of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
          KeyMap.lookup "issues" fields == Just (Array mempty),
          KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
        _ -> die ("Strict floating decode audit rejected " ++ stage ++ "/" ++ name)
      pure (reportPath:commandArtifacts audited)
    pure (corePaths ++ commandArtifacts exported ++ concat reports)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,example,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/FloatDecodeFixtures.hs",
        "compiler/build.sh","compiler/export.sh","compiler/export-boot.py","compiler/toolchain.sh","compiler/plugin.py",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json"] ++ vendorSources ++
        ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [requestPath,directory </> "oracle.tsv",binary,original,provenance] ++ concat stages ++
        commandArtifacts compiled ++ commandArtifacts executed ++ commandArtifacts bootExport
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "ghcInfo" .= info,"entries" .= entries,"nativeRows" .= length rows,
    "floatInputs" .= inputs 32,"doubleInputs" .= inputs 64,
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("float-decode: " ++ show (length rows) ++ " native rows; strict pre/post Core")
