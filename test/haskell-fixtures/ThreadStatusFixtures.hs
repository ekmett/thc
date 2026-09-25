-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
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
      source = "compiler/test-fixtures/ThreadStatusAudit.hs"
      driver = "compiler/test-fixtures/ThreadStatusNative.hs"
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
      "compiler/export.sh" (options ++ [source]) ""
    forM_ entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      _ <- run root [] "python3" ["scripts/audit-core.py", "--entry", entry,
        "--output", report, core </> "ThreadStatusAudit.json"] ""
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
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle") ["+RTS", "-N2", "-RTS"] ""
  unless (observations == "0\n0\n16\n17\n1\n14\n") (die "Native threadStatus# oracle disagreed")
  writeFile (output </> "oracle.txt") observations
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/ThreadStatusFixtures.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt"] ++
        [directory </> stage </> suffix | stage <- stages,
          suffix <- "core/ThreadStatusAudit.json" : [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "native" .= ([0, 0, 16, 17, 1, 14] :: [Int]),
    "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "thread-status: native statuses and capability/lock invariants, strict pre/post Core"
  where
    isThreadStatus (Object primitive) = KeyMap.lookup "name" primitive == Just (String "threadStatus#")
    isThreadStatus _ = False
