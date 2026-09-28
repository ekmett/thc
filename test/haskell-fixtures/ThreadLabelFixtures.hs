-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ThreadLabelFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for thread label.
module ThreadLabelFixtures (prepareThreadLabel) where

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

prepareThreadLabel :: FilePath -> IO ()
prepareThreadLabel root = do
  let directory = "build/thread-label"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "test/fixtures/compiler/ThreadLabelAudit.hs"
      driver = "test/fixtures/compiler/ThreadLabelNative.hs"
      entries = ["selfLabel", "overwriteLabel", "emptyLabel", "deadLabel", "deadOverwrite"]
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Thread label fixture requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    forM_ entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      _ <- run root [] "python3" ["bin/audit-core.py", "--entry", entry,
        "--output", report, core </> "ThreadLabelAudit.json"] ""
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
          KeyMap.lookup "issues" value == Just (Array mempty),
          KeyMap.lookup "missingGlobals" value == Just (Array mempty),
          Just (Array primitives) <- KeyMap.lookup "primitives" value,
          all (\name -> any (isPrimitive name) (toList primitives)) ["labelThread#", "threadLabel#"] -> pure ()
        _ -> die ("Strict threadLabel# audit did not accept " ++ entry)
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "test/fixtures/compiler"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle") ["+RTS", "-N2", "-RTS"] ""
  let checksum seed = foldl (\acc byte -> acc * 31 + byte) 4 [206, 187, 0, seed `mod` 128]
      expected = concat [[checksum seed, checksum (seed + 1), 0, checksum seed, checksum seed]
        | seed <- [0, 65, 127]] :: [Int]
  unless (observations == unlines (map show expected)) (die "Native thread labels disagreed")
  writeFile (output </> "oracle.txt") observations
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/ThreadLabelFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt"] ++
        [directory </> stage </> suffix | stage <- stages,
          suffix <- "core/ThreadLabelAudit.json" : [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "native" .= expected,
    "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "thread-label: native UTF-8 bytes, overwrite, empty and terminated-thread labels, strict pre/post Core"
  where
    isPrimitive name (Object primitive) = KeyMap.lookup "name" primitive == Just (String name)
    isPrimitive _ _ = False
