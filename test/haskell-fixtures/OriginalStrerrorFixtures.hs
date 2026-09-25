-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module OriginalStrerrorFixtures (prepareOriginalStrerror) where

import Control.Monad (unless)
import Data.Aeson (object, toJSON, (.=))
import qualified Data.ByteString.Char8 as BS
import qualified Data.Map.Strict as Map
import FixtureSupport
import System.Directory (createDirectoryIfMissing)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)

prepareOriginalStrerror :: FilePath -> IO ()
prepareOriginalStrerror root = do
  let directory = "build/original-strerror"
      native = directory </> "native"
      binary = native </> "oracle"
      source = "compiler/test-fixtures/OriginalStrerrorNative.hs"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original strerror requires GHC 9.14.1")
  _ <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-package", "ghc-internal", "-odir", root </> native, "-hidir", root </> native,
    source, "-o", root </> binary]
  observed <- execute "native-oracle" [("LC_ALL", "C")] (root </> binary) []
  rows <- maybe (die "Malformed original strerror native oracle") pure
    (readMaybe (BS.unpack (commandStdout observed)) :: Maybe [(Int, String)])
  unless (map fst rows == [2, 22] && all (not . null . snd) rows &&
          BS.null (commandStderr observed)) (die "Incomplete original strerror native oracle")
  let oracle = directory </> "oracle.json"
      manifest = directory </> "manifest.json"
  writeJson (root </> oracle) (toJSON [object ["errno" .= number, "message" .= message] | (number, message) <- rows])
  sourceHash <- hashFile (root </> source)
  oracleHash <- hashFile (root </> oracle)
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length rows, "inputHashes" .= Map.singleton source sourceHash,
    "artifactHashes" .= Map.singleton oracle oracleHash]
