-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ThreadSchedulingFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for thread scheduling.
module ThreadSchedulingFixtures (prepareThreadScheduling) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.List (sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareThreadScheduling :: FilePath -> IO ()
prepareThreadScheduling root = do
  let directory = "build/thread-scheduling"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/core/ThreadScheduling.hs"
      driver = "t/fixtures/compiler/ThreadSchedulingNative.hs"
      entries = ["emptySpark", "lazyPar", "lazySpark", "sparkValue", "currentCounter", "negativeCounter", "pinnedFork", "otherCounter", "timedDelay"]
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Thread scheduling requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    forM_ entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      _ <- run root [] "python3" ["bin/audit-core.py", "--entry", "main:ThreadScheduling." ++ entry,
        "--output", report, core </> "ThreadScheduling.cbd"] ""
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
          KeyMap.lookup "issues" value == Just (Array mempty),
          KeyMap.lookup "missingGlobals" value == Just (Array mempty) -> pure ()
        _ -> die ("Strict thread scheduling audit rejected " ++ entry)
  let native = output </> "native"
  createDirectoryIfMissing True native
  -- Direct delay# is implemented by the non-threaded POSIX I/O manager.
  -- The threaded RTS deliberately rejects it; base's threadDelay uses its event manager instead.
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "t/fixtures/core"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle") [] ""
  unless (observations == "1\n1\n1\n1\n1\n1\n11\n11\n2000\n") (die "Native thread scheduling disagreed")
  writeFile (output </> "oracle.txt") observations
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/ThreadSchedulingFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt"] ++
        [directory </> stage </> suffix | stage <- stages,
          suffix <- "core/ThreadScheduling.cbd" : [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "native" .= ([1, 1, 1, 1, 1, 1, 11, 11, 2000] :: [Int]),
    "nativeRTS" .= ("non-threaded: direct delay# uses the POSIX I/O manager" :: String),
    "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "thread-scheduling: nine entries, native hint/delay/fork/counter behavior, strict pre/post Core"
