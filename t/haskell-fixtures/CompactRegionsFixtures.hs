-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (158 compact-regions and compact-serialization)
-- Purpose: Check compact region sharing/cycles/rejection/interruption and serialized
--   round trips including cyclic, empty and multi-block regions.
-- Consumes: CompactRegionsAudit/Native or CompactSerializedAudit/Native sources,
--   complete ghc-compact dependency Core, GHC, exporter and auditor.
-- Produces/consumed result: Each family owns pre/post CBDs, originals, oracle.tsv and
--   manifest under build/compact-regions or build/compact-serialization.
-- Cost and overlap: THC-managed compact semantics justify these cases. Share package
--   acquisition and native batching; fixture-free compact tests cover local mechanics.
-- Build status: QUARANTINED. Both producers require dependency discovery to fail
--   with one exact unrelated Conc.Bound error. Fixing that error breaks generation.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 158.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : CompactRegionsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for compact regions.
module CompactRegionsFixtures (prepareCompactRegions, prepareCompactSerialization) where

import Control.Monad (forM, unless, when)
import Codec.Archive.Zip (findEntryByPath, fromEntry, toArchiveOrFail)
import Data.Aeson (Value, object, toJSON, (.=))
import qualified Data.ByteString.Lazy as BL
import Data.List (isInfixOf, isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import FixtureSupport
import InstalledCoreFixtures
import System.Directory
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath

prepareCompactRegions :: FilePath -> IO ()
prepareCompactRegions root = prepareCompactFixture root "build/compact-regions"
  "t/fixtures/compiler/CompactRegionsAudit.hs" "t/fixtures/compiler/CompactRegionsNative.hs"
  ["ordinary", "sharing", "cycleCase", "rejectedObjects", "frozenArray", "interruptedPlain", "interruptedSharing"]

prepareCompactSerialization :: FilePath -> IO ()
prepareCompactSerialization root = prepareCompactFixture root "build/compact-serialization"
  "t/fixtures/compiler/CompactSerializedAudit.hs" "t/fixtures/compiler/CompactSerializedNative.hs"
  ["roundTrip", "cycleRoundTrip", "multipleBlocks", "emptyRoundTrip"]

prepareCompactFixture :: FilePath -> FilePath -> FilePath -> FilePath -> [String] -> IO ()
prepareCompactFixture root directory source driver entries = do
  let manifest = root </> directory </> "manifest.json"
      logs = directory </> "commands"
  createDirectoryIfMissing True (root </> directory)
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  installed <- prepareInstalledCoreUnits root directory ["ghc-compact"]
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        consumer = stageDir </> "core" </> takeBaseName source ++ ".cbd"
    _ <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> stageDir </> "core"), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ [source])
    -- Preserve and examine whole-package discovery, then select whole original
    -- reachable modules. Unrelated Conc.Bound export registration is not loaded.
    let discoveryPath = stageDir </> "dependency-discovery.json"
    _ <- runLoggedExpect 1 300 root logs (stage ++ "-discovery") [] "python3"
      (["bin/audit-core.py", "--package-manifest", fixturePackages installed,
        "--output", discoveryPath] ++ concat [["--entry", entry] | entry <- entries] ++ [consumer])
    discovery <- readJson (root </> discoveryPath)
    missing <- field discovery "missingGlobals" :: IO [Value]
    issues <- field discovery "issues" :: IO [Value]
    reached <- field discovery "reachableBindings" :: IO [Value]
    sources <- mapM (\record -> field record "source" :: IO String) reached
    case issues of
      [issue] -> do
        code <- field issue "code" :: IO String
        detail <- field issue "detail" :: IO String
        path <- field issue "path" :: IO String
        unless (null missing && code == "module-format" && "GHC.Internal.Conc.Bound" `isInfixOf` detail && path `notElem` sources)
          (die "Compact dependency discovery has an actual unsupported frontier")
      _ -> die "Compact dependency discovery issue inventory changed"
    let originals = sort (nub (filter (isInfixOf "!/") sources))
        splitOrigin origin = let (archive, member) = Text.breakOn "!/" (Text.pack origin)
                             in (Text.unpack archive, Text.unpack (Text.drop 2 member))
    archives <- forM (nub (map (fst . splitOrigin) originals)) $ \path -> do
      unless ((root </> directory </> "installed/bundles/") `isPrefixOf` path)
        (die "Compact module did not come from selected installed Core")
      bytes <- BL.readFile path
      archive <- either die pure (toArchiveOrFail bytes)
      pure (path, archive)
    createDirectoryIfMissing True (root </> stageDir </> "originals")
    selected <- forM (zip [0 :: Int ..] originals) $ \(index, origin) -> do
      let (archivePath, member) = splitOrigin origin
          output = stageDir </> "originals" </> show index ++ ".cbd"
      archive <- maybe (die "Missing compact original archive") pure (lookup archivePath archives)
      original <- maybe (die "Missing compact original module") pure (findEntryByPath member archive)
      BL.writeFile (root </> output) (fromEntry original)
      pure (output, makeRelative root archivePath ++ "!/" ++ member)
    writeJson (root </> stageDir </> "original-selection.json") (toJSON (Map.fromList selected))
    let modules = consumer : map fst selected
    audits <- forM entries $ \entry -> do
      let path = stageDir </> entry ++ "-audit.json"
      _ <- runLogged 180 root logs (stage ++ "-" ++ entry ++ "-audit") [] "python3"
        (["bin/audit-core.py", "--entry", entry, "--output", path] ++ modules)
      pure path
    pure (stage, modules, audits ++ [discoveryPath, stageDir </> "original-selection.json"])
  let native = directory </> "native"
  createDirectoryIfMissing True (root </> native)
  _ <- runLogged 180 root logs "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-it/fixtures/compiler", "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observed <- runLogged 60 root logs "native-oracle" [] (root </> native </> "oracle") []
  let oracle = directory </> "oracle.tsv"
  BL.writeFile (root </> oracle) (BL.fromStrict (commandStdout observed))
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root (sort $ [source, driver, "t/haskell-fixtures/CompactRegionsFixtures.hs",
    "t/haskell-fixtures/InstalledCoreFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "t/haskell-fixtures/Main.hs", "thc.cabal", "bin/export-core.sh", "bin/build-compiler.sh",
    "bin/toolchain.sh", "bin/plugin.py", "bin/audit-core.py", "bin/core-capabilities.json"] ++
    ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root (oracle : (native </> "oracle") : fixtureArtifacts installed ++
    concat [modules ++ audits | (_, modules, audits) <- stages])
  writeJson manifest (object ["schema" .= (1 :: Int), "entries" .= entries,
    "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes])
  putStrLn (directory ++ ": original ghc-compact examples, native oracle and strict pre/post Core")
