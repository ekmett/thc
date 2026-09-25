-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module BoundThreadQueryFixtures (prepareBoundThreadQuery) where

import Control.Exception (try)
import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import qualified THC.Driver.Installed as Installed
import InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore, field, readJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Exit (ExitCode, die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

directory, source, nativeSource :: FilePath
directory = "build/bound-thread-query"
source = "compiler/test-fixtures/BoundThreadQueryAudit.hs"
nativeSource = "compiler/test-fixtures/BoundThreadQueryNative.hs"

values :: [Integer]
values = [negate (2 ^ (63 :: Int)), -4097, -1, 0, 1, 42, 4097, 2 ^ (63 :: Int) - 1]

prepareBoundThreadQuery :: FilePath -> IO ()
prepareBoundThreadQuery root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
      execute label = runLogged 180 root (directory </> "logs") label
  createDirectoryIfMissing True output
  -- Rejected refreshes must not leave stale successful closure/native receipts.
  -- Command streams remain separate; native success is recorded before auditing.
  forM_ ["manifest.json", "native-receipt.json", "pre/audit.json", "post/audit.json"] $ \name -> do
    let path = output </> name
    old <- doesFileExist path
    when old (removeFile path)
  -- The shared provider may select a private complete-Core interface view,
  -- while the native controls always use its ordinary selected compiler.
  installed <- prepareInstalledCore root directory
  let ghc = fixtureGhc installed
      packagePath = fixturePackages installed
      selected = fixtureContext installed
  libdir <- execute "ghc-libdir" [] ghc ["--print-libdir"]
  nativeRuns <- forM [("nonthreaded", False), ("threaded", True)] $ \(mode, threaded) -> do
    let native = directory </> mode
        binary = native </> "oracle"
        oracle = native </> "oracle.tsv"
    createDirectoryIfMissing True (root </> native)
    built <- execute (mode ++ "-build") [] ghc (["--make", "-O2", "-fforce-recomp", "-Wall", "-Werror",
      "-dcore-lint", "-dstg-lint", "-i" ++ (root </> "compiler/test-fixtures"),
      "-odir", root </> native, "-hidir", root </> native, nativeSource, "-o", root </> binary] ++ ["-threaded" | threaded])
    observed <- runLogged 30 root (directory </> "logs") (mode ++ "-oracle") [] (root </> binary) []
    (controls, rows) <- case lines (BS.unpack (commandStdout observed)) of
      first:rest | Just flags <- (readMaybe first :: Maybe (Bool, Bool, Bool, Bool, Bool)) -> pure (flags, rest)
      _ -> die "Malformed native bound-thread query controls"
    unless (controls == (threaded, threaded, False, threaded, threaded) && BS.null (commandStderr observed))
      (die ("Native bound/current-thread distinction failed: " ++ show controls))
    let parsed = traverse (\line -> case words line of
          [input, result] -> (,) <$> (readMaybe input :: Maybe Integer) <*> readMaybe result
          _ -> Nothing) rows
        signed n = (n + 2 ^ (63 :: Int)) `mod` 2 ^ (64 :: Int) - 2 ^ (63 :: Int)
        expected = [(input, signed (input + if threaded then 1 else 0)) | input <- values]
    unless (parsed == Just expected) (die "Native bound-thread query scalar projection mismatch")
    writeFile (root </> oracle) (unlines rows)
    pure (mode, controls, oracle, commandArtifacts built ++ commandArtifacts observed)
  nativeInputHashes <- hashes root [source, nativeSource]
  nativeArtifactHashes <- hashes root $ concat
    [(directory </> mode </> "oracle") : oracle : logs | (mode, _, oracle, logs) <- nativeRuns]
  writeJson (output </> "native-receipt.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "producerRoot" .= root,
     "installedCompiler" .= Installed.installedCompiler selected, "ghcLibdir" .= BS.unpack (commandStdout libdir),
     "nativeRowsPerMode" .= length values, "nativeControls" .= Map.fromList [(mode, flags) | (mode, flags, _, _) <- nativeRuns],
     "inputHashes" .= nativeInputHashes, "artifactHashes" .= nativeArtifactHashes, "runtimeVerified" .= False]
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "BoundThreadQueryAudit.json"]
    exported <- execute (stage ++ "-export") [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=boundThreadQuery", source])
    actual <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (actual == ["BoundThreadQueryAudit.json", "THC.InterfaceClosure.json"])
      (die ("Unexpected bound-thread query module inventory: " ++ show actual))
    audited <- try (execute (stage ++ "-audit") [] "python3" (["scripts/audit-core.py", "--package-manifest", packagePath, "--entry", "boundThreadQuery",
      "--output", stageDir </> "audit.json"] ++ modules)) :: IO (Either ExitCode CommandResult)
    pure (stage, modules, stageDir </> "audit.json", exported, audited)
  let failed = [stage | (stage, _, _, _, Left _) <- stages]
  unless (null failed) (die ("Bound-thread query strict audits failed: " ++ unwords failed ++
    ". Both attempts and native-receipt.json retained; no complete success manifest written."))
  forM_ stages $ \(_, _, audit, _, _) -> do
    report <- readJson (root </> audit)
    case report of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
        KeyMap.lookup "issues" fields == Just (Array mempty),
        KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
      _ -> die "Bound-thread query original closure did not pass strict audit"
    calls <- field report "foreignCalls" :: IO [Value]
    symbols <- mapM (`field` "symbol") calls :: IO [String]
    unless (not (null symbols) && all (== "rtsSupportsBoundThreads") symbols)
      (die "Original installed bound-thread query missing or unexpected foreign obligation")
  plugins <- listDirectory (root </> "compiler/THC")
  drivers <- listDirectory (root </> "src/THC/Driver")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, nativeSource, "test/haskell-fixtures/BoundThreadQueryFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/InstalledCoreFixtures.hs",
        "test/haskell-fixtures/Main.hs", "thc.cabal", "cabal.project",
        "compiler/interface/Main.hs", "compiler/target-layout.c",
        "scripts/core-capabilities.json", "scripts/audit-core.py", "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/plugin.py", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh"] ++
        ["compiler/THC" </> name | name <- plugins, takeExtension name == ".hs"] ++
        ["src/THC/Driver" </> name | name <- drivers, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      artifacts = fixtureArtifacts installed ++
        concatMap commandArtifacts (fixtureCommands installed ++ [libdir]) ++
        concat [oracle:logs | (_, _, oracle, logs) <- nativeRuns] ++
        [directory </> "native-receipt.json"] ++
        concat [modules ++ audit : commandArtifacts exported ++ commandArtifacts audited | (_, modules, audit, exported, Right audited) <- stages] ++
        [directory </> stage </> "core/THC.InterfaceClosure.json" | stage <- ["pre", "post"]]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "ghcLibdir" .= BS.unpack (commandStdout libdir), "producerRoot" .= root,
    "installedArtifactsHashed" .= False, "entry" .= ("boundThreadQuery" :: String),
    "packageManifest" .= packagePath, "installedCompiler" .= Installed.installedCompiler selected,
    "nativeRowsPerMode" .= length values, "nativeControls" .= Map.fromList [(mode, flags) | (mode, flags, _, _) <- nativeRuns],
    "stages" .= Map.fromList [(stage, modules) | (stage, modules, _, _, _) <- stages],
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "bound-thread-query: original import, 8 rows in each native RTS mode, pre/post strict closure; negative THC capability only"
