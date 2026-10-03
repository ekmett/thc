-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (160 signal-dispatch)
-- Purpose: Check original signal handlers reach THC scheduling with correct masking,
--   callback results, resource restoration and no leaked handoff references.
-- Consumes: SignalDispatchAudit.hs/SignalDispatchNative.hs, GHC threaded RTS,
--   installed Core/native package closure, optional GHC sources, exporter and auditor.
-- Produces/consumed result: Pre/post CBDs, four native signal results, audits/manifest.
-- Cost and overlap: Runtime scheduling/restoration is a real boundary beyond libc;
--   share package acquisition and retain this focused integration coverage.
-- Build status: Review candidate; no incidental root count or output glob found.
--   Inventory does not establish execution or graph admission.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 160.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : SignalDispatchFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for signal dispatch.
module SignalDispatchFixtures (prepareSignalDispatch) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore, prepareInstalledCoreWithForeign)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareSignalDispatch :: FilePath -> IO ()
prepareSignalDispatch root = do
  let directory = "build/signal-dispatch"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/SignalDispatchAudit.hs"
      driver = "t/fixtures/compiler/SignalDispatchNative.hs"
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
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-i./t/fixtures/compiler",
    "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"] ""
  observed <- runWithTimeout (Just 30000000) root [] (root </> native </> "oracle") ["+RTS", "-N2"] ""
  unless (observed == "1\n10010\n2\n20020\n3\n30030\n15\n150150\n")
    (die ("Original GHC signal dispatcher oracle mismatch: " ++ observed))
  writeFile (root </> oracle) observed
  _ <- run root [] "bin/build-compiler.sh" [] ""
  sourceRoot <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
  installed <- maybe (prepareInstalledCore root directory)
    (prepareInstalledCoreWithForeign root directory) sourceRoot
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        consumer = core </> "SignalDispatchAudit.cbd"
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=auditMain"] ++
        ["-package", "ghc-internal", source]) ""
    -- Original forkIO reaches its uncaught exception handler and Posix Handle
    -- dependencies. They require the production annotated installed Core view;
    -- no source, metadata, module or binding is filtered to bypass admission.
    _ <- run root [] "python3" ["bin/audit-core.py", "--package-manifest", fixturePackages installed,
      "--entry", "auditMain", "--io-main", "--output", stageDir </> "audit.json", consumer] ""
    pure (stage, [consumer])
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  drivers <- listDirectory (root </> "src/driver/THC/Driver")
  inputHashes <- hashes root (sort $ [source, driver, "t/haskell-fixtures/SignalDispatchFixtures.hs",
    "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal",
    "t/haskell-fixtures/InstalledCoreFixtures.hs", "bin/build-compiler.sh", "bin/export-core.sh",
    "bin/toolchain.sh", "bin/plugin.py", "src/compiler/interface/Main.hs", "src/driver/cbits/target-layout.c",
    "bin/audit-core.py", "bin/core-capabilities.json"] ++
    ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["src/driver/THC/Driver" </> name | name <- drivers, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root (oracle : fixtureArtifacts installed ++ concat [modules ++
    [directory </> stage </> "audit.json"]
    | (stage, modules) <- stages])
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= Map.fromList stages, "packageManifest" .= fixturePackages installed,
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "signal-dispatch: original handler registry/forkIO, four signals, unmasked native results, strict pre/post Core"
