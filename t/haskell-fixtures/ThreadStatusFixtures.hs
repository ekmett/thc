-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (109 thread-status)
-- Purpose: Check reported thread status reflects live, masked, blocked and completed
--   states.
-- Produces/consumed result: CBDs and oracle.txt.
-- Cost and overlap: Keep observable states, synchronized to events rather than
--   timing/order. Share thread setup; historical report inventories add no confidence.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 109.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ThreadStatusFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for thread status.
module ThreadStatusFixtures (prepareThreadStatus) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareThreadStatus :: FilePath -> IO ()
prepareThreadStatus root = do
  let directory = "build/thread-status"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/ThreadStatusAudit.hs"
      driver = "t/fixtures/compiler/ThreadStatusNative.hs"
      entries = ["selfStatus", "maskedStatus", "finishedStatus", "diedStatus", "blockedStatus"]
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Thread status fixture requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    forM_ entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      _ <- run root [] "python3" ["bin/audit-core.py", "--entry", "main:ThreadStatusAudit." ++ entry,
        "--output", report, core </> "ThreadStatusAudit.cbd"] ""
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
          KeyMap.lookup "issues" value == Just (Array mempty),
          KeyMap.lookup "missingGlobals" value == Just (Array mempty),
          Just (Array primitives) <- KeyMap.lookup "primitives" value,
          any isThreadStatus (toList primitives) -> pure ()
        _ -> die ("Strict threadStatus# audit did not accept " ++ entry)
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "t/fixtures/compiler"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle") ["+RTS", "-N2", "-RTS"] ""
  unless (observations == "0\n0\n16\n17\n1\n14\n") (die "Native threadStatus# oracle disagreed")
  writeFile (output </> "oracle.txt") observations
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/ThreadStatusFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt"] ++
        [directory </> stage </> suffix | stage <- stages,
          suffix <- "core/ThreadStatusAudit.cbd" : [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "native" .= ([0, 0, 16, 17, 1, 14] :: [Int]),
    "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "thread-status: native statuses and capability/lock invariants, strict pre/post Core"
  where
    isThreadStatus (Object primitive) = KeyMap.lookup "name" primitive == Just (String "threadStatus#")
    isThreadStatus _ = False
