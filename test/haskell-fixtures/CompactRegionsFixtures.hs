-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module CompactRegionsFixtures (prepareCompactRegions) where

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

entries :: [String]
entries = ["ordinary", "sharing", "cycleCase", "rejectedObjects", "frozenArray"]

prepareCompactRegions :: FilePath -> IO ()
prepareCompactRegions root = do
  let directory = "build/compact-regions"
      manifest = root </> directory </> "manifest.json"
      source = "compiler/test-fixtures/CompactRegionsAudit.hs"
      driver = "compiler/test-fixtures/CompactRegionsNative.hs"
      logs = directory </> "commands"
  createDirectoryIfMissing True (root </> directory)
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  installed <- prepareInstalledCoreUnits root directory ["ghc-compact"]
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        consumer = stageDir </> "core/CompactRegionsAudit.json"
    _ <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> stageDir </> "core"), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ [source])
    -- Preserve and examine whole-package discovery, then select whole original
    -- reachable modules. Unrelated Conc.Bound export registration is not loaded.
    let discoveryPath = stageDir </> "dependency-discovery.json"
    _ <- runLoggedExpect 1 300 root logs (stage ++ "-discovery") [] "python3"
      (["scripts/audit-core.py", "--package-manifest", fixturePackages installed,
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
          output = stageDir </> "originals" </> show index ++ ".json"
      archive <- maybe (die "Missing compact original archive") pure (lookup archivePath archives)
      original <- maybe (die "Missing compact original module") pure (findEntryByPath member archive)
      BL.writeFile (root </> output) (fromEntry original)
      pure (output, makeRelative root archivePath ++ "!/" ++ member)
    writeJson (root </> stageDir </> "original-selection.json") (toJSON (Map.fromList selected))
    let modules = consumer : map fst selected
    audits <- forM entries $ \entry -> do
      let path = stageDir </> entry ++ "-audit.json"
      _ <- runLogged 180 root logs (stage ++ "-" ++ entry ++ "-audit") [] "python3"
        (["scripts/audit-core.py", "--entry", entry, "--output", path] ++ modules)
      pure path
    pure (stage, modules, audits ++ [discoveryPath, stageDir </> "original-selection.json"])
  let native = directory </> "native"
  createDirectoryIfMissing True (root </> native)
  _ <- runLogged 180 root logs "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-icompiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observed <- runLogged 60 root logs "native-oracle" [] (root </> native </> "oracle") []
  let oracle = directory </> "oracle.tsv"
  BL.writeFile (root </> oracle) (BL.fromStrict (commandStdout observed))
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root (sort $ [source, driver, "test/haskell-fixtures/CompactRegionsFixtures.hs",
    "test/haskell-fixtures/InstalledCoreFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "test/haskell-fixtures/Main.hs", "thc.cabal", "compiler/export.sh", "compiler/build.sh",
    "compiler/toolchain.sh", "compiler/plugin.py", "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root (oracle : (native </> "oracle") : fixtureArtifacts installed ++
    concat [modules ++ audits | (_, modules, audits) <- stages])
  writeJson manifest (object ["schema" .= (1 :: Int), "entries" .= entries,
    "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes])
  putStrLn "compact-regions: original ghc-compact examples, native oracle and strict pre/post Core"
