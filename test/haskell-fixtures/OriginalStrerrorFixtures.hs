-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module OriginalStrerrorFixtures (prepareOriginalStrerror) where

import Control.Monad (unless)
import Data.Aeson (object, (.=))
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
  (messages, raw) <- maybe (die "Malformed original strerror native oracle") pure
    (readMaybe (BS.unpack (commandStdout observed)) :: Maybe ([(Int, String)], [(Int, Int, Int, [Int])]))
  unless (map fst messages == [2, 22] && all (not . null . snd) messages &&
          map (\(number, size, _, _) -> (number, size)) raw ==
            [(22, 512), (999999, 512), (22, 4), (22, 8)] &&
          all (\(_, size, _, bytes) -> size == length bytes && all (\byte -> byte >= 0 && byte <= 255) bytes) raw &&
          BS.null (commandStderr observed)) (die "Incomplete original strerror native oracle")
  let oracle = directory </> "oracle.json"
      manifest = directory </> "manifest.json"
  writeJson (root </> oracle) $ object
    ["messages" .= [object ["errno" .= number, "message" .= message] | (number, message) <- messages],
     "raw" .= [object ["errno" .= number, "length" .= size, "status" .= status, "bytes" .= bytes]
       | (number, size, status, bytes) <- raw]]
  sourceHash <- hashFile (root </> source)
  oracleHash <- hashFile (root </> oracle)
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "locale" .= ("C" :: String), "nativeRows" .= (length messages + length raw),
    "inputHashes" .= Map.singleton source sourceHash,
    "artifactHashes" .= Map.singleton oracle oracleHash]
