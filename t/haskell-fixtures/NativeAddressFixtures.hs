-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (068 native-addresses)
-- Purpose: Check native pointer aliasing, copying and allocation transport.
-- Produces/consumed result: Native address oracle.json and malloc oracle.txt; tracked
--   malloc descriptors.
-- Cost and overlap: Keep THC FFI pointer/ownership behavior. Native allocation internals
--   are not the contract; fold these cases into one boundary suite.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 068.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : NativeAddressFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for native address.
module NativeAddressFixtures (prepareNativeAddress) where

import Control.Monad (unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import FixtureSupport (commandStdout, commandStderr, hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (os)
import Text.Read (readMaybe)

prepareNativeAddress :: FilePath -> IO ()
prepareNativeAddress root = do
  let directory = "build/native-addresses"
      native = directory </> "native"
      binary = native </> ("oracle" ++ if os == "mingw32" then ".exe" else "")
      source = "t/fixtures/compiler/NativeAddressNative.hs"
      oracle = "build/native-addresses/oracle.json"
      manifest = directory </> "manifest.json"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Native address oracle requires GHC 9.14.1")
  let linking = ["-dynamic" | os /= "mingw32"]
  _ <- execute "native-build" [] ghc (linking ++ ["--make", "-O2", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, source, "-o", root </> binary])
  observed <- execute "native-oracle" [] (root </> binary) []
  let parsed = case lines (BS.unpack (commandStdout observed)) of
        [observations] -> readMaybe observations :: Maybe [Bool]
        _ -> Nothing
  unless (parsed == Just (replicate 9 True) && BS.null (commandStderr observed))
    (die "Native address oracle does not match address coercion semantics")
  writeJson (root </> oracle) $ object ["observations" .= parsed]
  inputHashes <- hashes root [source, "t/haskell-fixtures/NativeAddressFixtures.hs",
    "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal"]
  artifactHashes <- hashes root [oracle]
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "native-addresses: nine native checks for coercions, offsets and aliases"
  -- Keep explicit malloc/free ownership in this existing address composite.
  let mallocDirectory = "build/native-malloc"
      mallocNative = mallocDirectory </> "native"
      mallocSource = "t/fixtures/compiler/NativeMallocNative.hs"
      mallocBinary = mallocNative </> ("oracle" ++ if os == "mingw32" then ".exe" else "")
      mallocOracle = "build/native-malloc/oracle.txt"
      mallocManifest = mallocDirectory </> "manifest.json"
      mallocExecute = runLogged 120 root (mallocDirectory </> "logs")
  createDirectoryIfMissing True (root </> mallocNative)
  mallocPresent <- doesFileExist (root </> mallocManifest)
  when mallocPresent (removeFile (root </> mallocManifest))
  _ <- mallocExecute "native-build" [] ghc (linking ++ ["--make", "-O2", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-odir", root </> mallocNative,
    "-hidir", root </> mallocNative, mallocSource, "-o", root </> mallocBinary])
  mallocObserved <- mallocExecute "native-oracle" [] (root </> mallocBinary) []
  unless (commandStdout mallocObserved == "0 0 0 0\n1 1 257 1\n2 2 514 2\n197 197 50629 197\n" &&
    BS.null (commandStderr mallocObserved)) (die "Native malloc/free alias oracle mismatch")
  BS.writeFile (root </> mallocOracle) (commandStdout mallocObserved)
  mallocInputs <- hashes root [mallocSource, "t/haskell-fixtures/NativeAddressFixtures.hs",
    "t/haskell-fixtures/FixtureSupport.hs", "src/test/resources/core/original-malloc-descriptors.json"]
  mallocArtifacts <- hashes root [mallocOracle]
  writeJson (root </> mallocManifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= mallocInputs, "artifactHashes" .= mallocArtifacts]
  putStrLn "native-malloc: four original malloc/free/copy alias rows"
