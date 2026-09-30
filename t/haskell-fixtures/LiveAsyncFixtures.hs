-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : LiveAsyncFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for live async.
module LiveAsyncFixtures (prepareLiveAsync) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareLiveAsync :: FilePath -> IO ()
prepareLiveAsync root = do
  let directory = "build/live-async"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/LiveAsyncAudit.hs"
      driver = "t/fixtures/compiler/LiveAsyncNative.hs"
      entries = ["forceShared", "strictWorker", "strictCall", "strictEntry", "takeReady", "takeRunning", "releaseGate", "prefixCount", "warmLoop", "asyncPayload"]
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Live async fixture requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core),
      ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    forM_ entries $ \entry -> do
      _ <- run root [] "python3" ["bin/audit-core.py", "--entry", "main:LiveAsyncAudit." ++ entry,
        "--output", directory </> stage </> (entry ++ "-audit.json"),
        core </> "LiveAsyncAudit.cbd"] ""
      pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "t/fixtures/compiler", "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["+RTS", "-N2", "-RTS"] ""
  unless (actual == "1007\n-1\n10000008\n1\n1031\n")
    (die "Live async native oracle disagreed with interrupted-thunk resumption")
  writeFile (output </> "oracle.txt") actual
  strictActual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["strict", "+RTS", "-N2", "-RTS"] ""
  unless (strictActual == actual)
    (die "Live async strict-callee native oracle disagreed with interrupted-thunk resumption")
  writeFile (output </> "strict-oracle.txt") strictActual
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/LiveAsyncFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt", directory </> "strict-oracle.txt"] ++ [directory </> stage </> suffix |
        stage <- stages,
        suffix <- "core/LiveAsyncAudit.cbd" : [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "native" .= ([1007, -1, 10000008, 1, 1031] :: [Int]),
    "nativeStrict" .= ([1007, -1, 10000008, 1, 1031] :: [Int]),
    "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
    "installedArtifactsHashed" .= False]
  putStrLn "live-async: native throwTo/catch#/shared-thunk resumption, prefix once, strict pre/post Core"
