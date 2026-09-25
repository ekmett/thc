-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module LibdwUnavailableFixtures (prepareLibdwUnavailable) where

import Control.Monad (unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import FixtureSupport (commandStdout, commandStderr, hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)

prepareLibdwUnavailable :: FilePath -> IO ()
prepareLibdwUnavailable root = do
  let directory = "build/libdw-unavailable"
      native = directory </> "native"
      binary = native </> "oracle"
      source = "compiler/test-fixtures/LibdwUnavailableNative.hs"
      oracle = directory </> "oracle.json"
      manifest = directory </> "manifest.json"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Libdw oracle requires GHC 9.14.1")
  _ <- execute "native-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, source, "-o", root </> binary]
  observed <- execute "native-oracle" [] (root </> binary) []
  let parsed = case lines (BS.unpack (commandStdout observed)) of
        ["USE_LIBDW=0", observations] -> readMaybe observations :: Maybe [Bool]
        _ -> Nothing
  unless (parsed == Just (replicate 8 True) && BS.null (commandStderr observed))
    (die "Libdw oracle does not match unavailable RTS semantics")
  writeJson (root </> oracle) $ object ["useLibdw" .= (False :: Bool), "observations" .= parsed]
  inputHashes <- hashes root [source, "test/haskell-fixtures/LibdwUnavailableFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal"]
  artifactHashes <- hashes root [oracle]
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "libdw-unavailable: eight native checks, including original collectStackTrace=Nothing"
