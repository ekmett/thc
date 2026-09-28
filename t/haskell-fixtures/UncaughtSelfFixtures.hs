-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : UncaughtSelfFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for uncaught self.
module UncaughtSelfFixtures (prepareUncaughtSelf) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (sort)
import FixtureSupport (hashes, run, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>), takeExtension)
import System.Process (CreateProcess(cwd), proc, readCreateProcessWithExitCode)

prepareUncaughtSelf :: FilePath -> IO ()
prepareUncaughtSelf root = do
  let directory = "build/uncaught-self"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/UncaughtSelfAudit.hs"
      driver = "t/fixtures/compiler/UncaughtSelfNative.hs"
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Uncaught self fixture requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core),
      ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    forM_ [("selfUncaught", "audit.json"), ("selfUncaughtIO", "io-audit.json")] $ \(entry, reportName) -> do
      let report = directory </> stage </> reportName
      (status, _, errors) <- readCreateProcessWithExitCode
        ((proc "python3" (["bin/audit-core.py", "--entry", entry] ++
          ["--io-main" | entry == "selfUncaughtIO"] ++
          ["--output", report, core </> "UncaughtSelfAudit.json"])) { cwd = Just root }) ""
      unless (status == ExitSuccess) (die ("Uncaught self Core audit failed: " ++ errors))
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just (Object audit) -> do
          let names = case KeyMap.lookup "primitives" audit of
                Just (Array primitives) -> [name | Object primitive <- toList primitives,
                  Just (String name) <- [KeyMap.lookup "name" primitive]]
                _ -> []
          unless (KeyMap.lookup "accepted" audit == Just (Bool True) &&
            KeyMap.lookup "missingGlobals" audit == Just (Array mempty) &&
            KeyMap.lookup "issues" audit == Just (Array mempty) &&
            all (`elem` names) ["myThreadId#", "killThread#"] && "catch#" `notElem` names)
            (die "Uncaught self Core lacks the exact uncaught thread-primitive path")
        _ -> die "Uncaught self Core audit is malformed"
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "t/fixtures/compiler", "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  let sources = sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/UncaughtSelfFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"]
      artifacts = (directory </> "native/oracle") :
        [directory </> stage </> suffix | stage <- stages,
          suffix <- ["core/UncaughtSelfAudit.json", "audit.json", "io-audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entry" .= ("selfUncaught" :: String), "stages" .= stages,
    "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "uncaught-self: strict GHC Core and separate genuine native exception oracle"
