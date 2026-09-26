-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module SignalDispatchFixtures (prepareSignalDispatch) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareSignalDispatch :: FilePath -> IO ()
prepareSignalDispatch root = do
  let directory = "build/signal-dispatch"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/SignalDispatchAudit.hs"
      driver = "compiler/test-fixtures/SignalDispatchNative.hs"
      entries = ["setupHandler", "awaitHandler", "ghc-internal:GHC.Internal.Conc.Signal.runHandlersPtr"] :: [String]
      native = directory </> "native"
      oracle = directory </> "oracle.txt"
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Signal dispatcher requires GHC 9.14.1")
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-fforce-recomp",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-i./compiler/test-fixtures",
    "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"] ""
  observed <- runWithTimeout (Just 30000000) root [] (root </> native </> "oracle") ["+RTS", "-N2"] ""
  unless (observed == "1\n10010\n2\n20020\n3\n30030\n15\n150150\n")
    (die ("Original GHC signal dispatcher oracle mismatch: " ++ observed))
  writeFile (root </> oracle) observed
  installed <- prepareInstalledCore root directory
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        consumer = core </> "SignalDispatchAudit.json"
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=auditMain"] ++
        ["-package", "ghc-internal", source]) ""
    -- Original forkIO reaches its uncaught exception handler and Posix Handle
    -- dependencies. They require the production annotated installed Core view;
    -- no source, metadata, module or binding is filtered to bypass admission.
    _ <- run root [] "python3" ["scripts/audit-core.py", "--package-manifest", fixturePackages installed,
      "--entry", "auditMain", "--io-main", "--output", stageDir </> "audit.json", consumer] ""
    pure (stage, [consumer])
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  drivers <- listDirectory (root </> "src/THC/Driver")
  inputHashes <- hashes root (sort $ [source, driver, "test/haskell-fixtures/SignalDispatchFixtures.hs",
    "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
    "test/haskell-fixtures/InstalledCoreFixtures.hs", "compiler/build.sh", "compiler/export.sh",
    "compiler/toolchain.sh", "compiler/plugin.py", "compiler/interface/Main.hs", "compiler/target-layout.c",
    "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["src/THC/Driver" </> name | name <- drivers, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root (oracle : fixtureArtifacts installed ++ concat [modules ++
    [directory </> stage </> "audit.json"]
    | (stage, modules) <- stages])
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= Map.fromList stages, "packageManifest" .= fixturePackages installed,
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "signal-dispatch: original handler registry/forkIO, four signals, unmasked native results, strict pre/post Core"
