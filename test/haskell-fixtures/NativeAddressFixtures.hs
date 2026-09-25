-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module NativeAddressFixtures (prepareNativeAddress) where

import Control.Monad (unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import FixtureSupport (commandStdout, commandStderr, hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)

prepareNativeAddress :: FilePath -> IO ()
prepareNativeAddress root = do
  let directory = "build/native-addresses"
      native = directory </> "native"
      binary = native </> "oracle"
      source = "compiler/test-fixtures/NativeAddressNative.hs"
      oracle = directory </> "oracle.json"
      manifest = directory </> "manifest.json"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Native address oracle requires GHC 9.14.1")
  _ <- execute "native-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, source, "-o", root </> binary]
  observed <- execute "native-oracle" [] (root </> binary) []
  let parsed = case lines (BS.unpack (commandStdout observed)) of
        [observations] -> readMaybe observations :: Maybe [Bool]
        _ -> Nothing
  unless (parsed == Just (replicate 9 True) && BS.null (commandStderr observed))
    (die "Native address oracle does not match address coercion semantics")
  writeJson (root </> oracle) $ object ["observations" .= parsed]
  inputHashes <- hashes root [source, "test/haskell-fixtures/NativeAddressFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal"]
  artifactHashes <- hashes root [oracle]
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "native-addresses: nine native checks for coercions, offsets and aliases"
  -- Keep explicit malloc/free ownership in this existing address composite.
  let mallocDirectory = "build/native-malloc"
      mallocNative = mallocDirectory </> "native"
      mallocSource = "compiler/test-fixtures/NativeMallocNative.hs"
      mallocBinary = mallocNative </> "oracle"
      mallocOracle = mallocDirectory </> "oracle.txt"
      mallocManifest = mallocDirectory </> "manifest.json"
      mallocExecute = runLogged 120 root (mallocDirectory </> "logs")
  createDirectoryIfMissing True (root </> mallocNative)
  mallocPresent <- doesFileExist (root </> mallocManifest)
  when mallocPresent (removeFile (root </> mallocManifest))
  _ <- mallocExecute "native-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-odir", root </> mallocNative,
    "-hidir", root </> mallocNative, mallocSource, "-o", root </> mallocBinary]
  mallocObserved <- mallocExecute "native-oracle" [] (root </> mallocBinary) []
  unless (commandStdout mallocObserved == "0 0 0 0\n1 1 257 1\n2 2 514 2\n197 197 50629 197\n" &&
    BS.null (commandStderr mallocObserved)) (die "Native malloc/free alias oracle mismatch")
  BS.writeFile (root </> mallocOracle) (commandStdout mallocObserved)
  mallocInputs <- hashes root [mallocSource, "test/haskell-fixtures/NativeAddressFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs", "src/test/resources/core/original-malloc-descriptors.json"]
  mallocArtifacts <- hashes root [mallocOracle]
  writeJson (root </> mallocManifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= mallocInputs, "artifactHashes" .= mallocArtifacts]
  putStrLn "native-malloc: four original malloc/free/copy alias rows"
