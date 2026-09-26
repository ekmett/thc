-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module STMFixtures (prepareSTM) where

import Control.Monad (forM, unless, when)
import Codec.Archive.Zip (findEntryByPath, fromEntry, toArchiveOrFail)
import Data.Aeson (Value, object, toJSON, (.=))
import qualified Data.ByteString.Lazy as BL
import Data.List (isInfixOf, isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import qualified Data.Text as T
import FixtureSupport (hashes, readInteger, run, runLoggedExpect, runWithTimeout, writeJson)
import InstalledCoreFixtures (InstalledFixture(..), field, readJson, prepareInstalledCore)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), makeRelative, takeExtension)

entries, contextEntries :: [String]
entries = ["basic", "rollback", "alternative", "lazyPayload", "nestedAtomic", "unliftedPayload"]
contextEntries = ["newCell", "readCell", "bumpCell", "awaitCell", "awaitEither"]
  ++ ["forceRetry", "forceInner", "asyncReady", "asyncRelease", "asyncSet", "asyncValue", "asyncPrefixes", "asyncPayload", "asyncEntry"]

prepareSTM :: FilePath -> IO ()
prepareSTM root = do
  let directory = "build/stm"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/STMAudit.hs"
      driver = "compiler/test-fixtures/STMNative.hs"
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "STM fixture requires GHC 9.14.1")
  installed <- prepareInstalledCore root directory
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        consumer = core </> "STMAudit.json"
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries ++ contextEntries] ++ [source]) ""
    -- Whole-package discovery encounters Conc.Bound's unrelated foreign export
    -- registration. Retain that failure, select ONLY whole original modules
    -- actually referenced by these roots, then require fresh strict acceptance.
    -- No binding, dictionary, metadata, or source bytes are rewritten.
    let discoveryPath = stageDir </> "dependency-discovery.json"
    _ <- runLoggedExpect 1 180 root (directory </> "logs") (stage ++ "-dependency-discovery") [] "python3"
      (["scripts/audit-core.py", "--package-manifest", fixturePackages installed,
        "--output", discoveryPath] ++ concat [["--entry", name] | name <- entries ++ contextEntries] ++ [consumer])
    discovery <- readJson (root </> discoveryPath)
    missing <- field discovery "missingGlobals" :: IO [Value]
    issues <- field discovery "issues" :: IO [Value]
    reachable <- field discovery "reachableBindings" :: IO [Value]
    sources <- mapM (\record -> field record "source" :: IO String) reachable
    case issues of
      [issue] -> do
        code <- field issue "code" :: IO String
        detail <- field issue "detail" :: IO String
        path <- field issue "path" :: IO String
        unless (null missing && code == "module-format" &&
          "GHC.Internal.Conc.Bound" `isInfixOf` detail && path `notElem` sources) $
          die "STM discovery has a real dependency gap; refusing to select around it"
      _ -> die "STM discovery issue inventory changed; original module selection requires review"
    let originals = sort (nub (filter (isInfixOf "!/") sources))
        splitOriginal origin = let (archive, member) = T.breakOn "!/" (T.pack origin)
                              in (T.unpack archive, T.unpack (T.drop 2 member))
    archives <- forM (nub (map (fst . splitOriginal) originals)) $ \path -> do
      unless ((root </> directory </> "installed/bundles/") `isPrefixOf` path) $
        die "STM original module did not come from the validated installed bundles"
      bytes <- BL.readFile path
      archive <- either die pure (toArchiveOrFail bytes)
      pure (path, archive)
    createDirectoryIfMissing True (root </> stageDir </> "originals")
    selected <- forM (zip [0 :: Int ..] originals) $ \(index, origin) -> do
      let (archivePath, member) = splitOriginal origin
          outputPath = stageDir </> "originals" </> show index ++ ".json"
      archive <- maybe (die "Lost original STM archive") pure (lookup archivePath archives)
      original <- maybe (die "Lost original STM module") pure (findEntryByPath member archive)
      BL.writeFile (root </> outputPath) (fromEntry original)
      pure (outputPath, makeRelative root archivePath ++ "!/" ++ member)
    writeJson (root </> stageDir </> "original-selection.json") (toJSON (Map.fromList selected))
    let modules = consumer : map fst selected
    mapM_ (\name -> do
      _ <- run root [] "python3" (["scripts/audit-core.py", "--entry", name,
        "--output", stageDir </> name ++ ".audit.json"] ++ modules) ""
      pure ()) (entries ++ contextEntries)
    pure (stage, modules)
  let native = output </> "native"
      executable = native </> "stm-oracle"
      oracle = directory </> "oracle.tsv"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-threaded", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures", "-odir", native, "-hidir", native,
    root </> driver, "-o", executable] ""
  observations <- runWithTimeout (Just 60000000) root [] executable ["+RTS", "-N2"] ""
  rows <- forM (lines observations) $ \line -> case words line of
    [name, input, result] | Just x <- readInteger input, Just _ <- readInteger result -> pure (name,x)
    _ -> die ("Malformed native STM row: " ++ line)
  let expected = Set.fromList ([(name,x) | name <- entries, x <- [-31,-1,0,1,17,63,4097]] ++
        [("concurrent",128),("either",0),("either",1)] ++
        [(name ++ "-" ++ observation,0) | name <- ["retry","inner"],
          observation <- ["caught","aborted","prefix","resumed","committed","prefix-after"]])
  unless (length rows == Set.size expected && Set.fromList rows == expected) $
    die "Native STM oracle has missing or duplicate rows"
  writeFile (root </> oracle) observations
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  drivers <- listDirectory (root </> "src/THC/Driver")
  inputHashes <- hashes root (sort $ [source, driver, "test/haskell-fixtures/STMFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
    "test/haskell-fixtures/InstalledCoreFixtures.hs",
    "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
    "compiler/interface/Main.hs", "compiler/target-layout.c",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["src/THC/Driver" </> name | name <- drivers, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root (oracle : fixtureArtifacts installed ++ concat [modules ++
    [directory </> stage </> "dependency-discovery.json", directory </> stage </> "original-selection.json"] ++
    [directory </> stage </> name ++ ".audit.json" | name <- entries ++ contextEntries] | (stage,modules) <- stages])
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "contextEntries" .= contextEntries, "stages" .= Map.fromList stages,
    "packageManifest" .= fixturePackages installed,
    "nativeRows" .= length rows, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("stm: 8 primops, pre/post Core, " ++ show (length rows) ++ " native observations")
