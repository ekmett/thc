-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (131 process-signals)
-- Purpose: Check guest signals and child-process host failures respect JVM/guest
--   isolation.
-- Produces/consumed result: oracle.txt, native-controls.txt and the Gradle-built thc
--   launcher.
-- Cost and overlap: Separate processes are necessary for signal isolation. Keep bounded
--   THC boundary cases; reuse the application launcher and do not rebuild it inside
--   fixture production.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 131.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ProcessSignalFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for process signal.
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

fixtureSources :: [FilePath]
fixtureSources = ["t/fixtures/compiler/ProcessSignalsNative.hs", "src/main/c/native-process-signal-api.c",
  "src/test/c/native-process-signals-test.c", "src/test/resources/core/original-signal-install-descriptor.json",
  "src/test/resources/core/original-unix-signal-install-descriptor.json",
  "t/haskell-fixtures/ProcessSignalFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs"]

prepareProcessSignals :: FilePath -> IO ()
prepareProcessSignals root | os /= "linux" || arch /= "x86_64" = do
  let directory = root </> "build/process-signals"
  createDirectoryIfMissing True directory
  inputHashes <- hashes root fixtureSources
  writeJson (directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "platform" .= os, "architecture" .= arch, "supported" .= False,
     "reason" .= ("Linux x86_64 native capture boundary only" :: String),
     "inputHashes" .= inputHashes, "artifactHashes" .= object []]
  putStrLn "process-signals: native capture explicitly excluded on this platform"
prepareProcessSignals root = do
  let directory = "build/process-signals"
      native = directory </> "native"
      source = "t/fixtures/compiler/ProcessSignalsNative.hs"
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
  unless (commandStdout observed == "[(1,[-1,-2,-4,-5]),(2,[-1,-2,-4,-5]),(3,[-1,-2,-4,-5]),(10,[-1,-2,-4,-5]),(12,[-1,-2,-4,-5]),(15,[-1,-2,-4,-5]),(24,[-1,-2,-4,-5]),(25,[-1,-2,-4,-5])]\n" && BS.null (commandStderr observed))
    (die "Native GHC signal action oracle mismatch")
  BS.writeFile (root </> oracle) (commandStdout observed)
  _ <- execute "capture-build" [] clang ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
    "src/test/c/native-process-signals-test.c", "-o", root </> native </> "capture-test", "-ldl"]
  captured <- execute "capture-child-controls" [] (root </> native </> "capture-test") []
  unless (commandStdout captured == "41 isolated native signal controls passed\n" && BS.null (commandStderr captured))
    (die "Native signal capture child controls failed")
  BS.writeFile (root </> controls) (commandStdout captured)
  inputHashes <- hashes root fixtureSources
  artifactHashes <- hashes root [oracle, controls]
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "platform" .= os, "architecture" .= arch, "supported" .= True,
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "process-signals: eight native GHC action oracles and 41 isolated machine capture controls"
