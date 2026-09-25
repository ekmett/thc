-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module ProcessSignalFixtures (prepareProcessSignals) where

import Control.Monad (unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import FixtureSupport (commandStdout, commandStderr, hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (os, arch)

prepareProcessSignals :: FilePath -> IO ()
prepareProcessSignals _ | os /= "linux" || arch /= "x86_64" =
  putStrLn "process-signals: Linux x86_64 native capture boundary only"
prepareProcessSignals root = do
  let directory = "build/process-signals"
      native = directory </> "native"
      source = "compiler/test-fixtures/ProcessSignalsNative.hs"
      oracle = directory </> "oracle.txt"
      controls = directory </> "native-controls.txt"
      manifest = directory </> "manifest.json"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  clang <- maybe "clang" id <$> lookupEnv "THC_CLANG"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Process signal oracle requires GHC 9.14.1")
  _ <- execute "native-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-odir", root </> native, "-hidir", root </> native,
    source, "-o", root </> native </> "oracle"]
  observed <- execute "native-oracle" [] (root </> native </> "oracle") []
  unless (commandStdout observed == "[-1,-2,-4,-5]\n" && BS.null (commandStderr observed))
    (die "Native GHC signal action oracle mismatch")
  BS.writeFile (root </> oracle) (commandStdout observed)
  _ <- execute "capture-build" [] clang ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
    "src/test/c/native-process-signals-test.c", "-o", root </> native </> "capture-test"]
  captured <- execute "capture-child-controls" [] (root </> native </> "capture-test") []
  unless (commandStdout captured == "9 isolated native signal controls passed\n" && BS.null (commandStderr captured))
    (die "Native signal capture child controls failed")
  BS.writeFile (root </> controls) (commandStdout captured)
  inputHashes <- hashes root [source, "src/main/c/native-process-signal-api.c",
    "src/test/c/native-process-signals-test.c", "src/test/resources/core/original-signal-install-descriptor.json",
    "test/haskell-fixtures/ProcessSignalFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs"]
  artifactHashes <- hashes root [oracle, controls]
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "process-signals: native GHC action order and nine isolated machine capture controls"
