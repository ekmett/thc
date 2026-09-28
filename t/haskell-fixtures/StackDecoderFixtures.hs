-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : StackDecoderFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for stack decoder.
module StackDecoderFixtures (prepareOriginalStackDecoder) where

import Control.Exception (try)
import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import InstalledCoreFixtures
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Exit (ExitCode, die)
import System.FilePath ((</>), takeExtension)

-- Full installed Core is required. The portable excerpt fixture and bounded
-- pinned-source overlay are intentionally not executable substitutes.
prepareOriginalStackDecoder :: FilePath -> IO ()
prepareOriginalStackDecoder root = do
  let directory = "build/original-stack-decoder"
      manifest = root </> directory </> "manifest.json"
      source = "t/fixtures/compiler/OriginalStackDecoder.hs"
      driver = "t/fixtures/compiler/OriginalStackDecoderNative.hs"
      entries = ["captureNamed", "observeSnapshot"]
      run label env program args = runLogged 300 root (directory </> "logs") label env program args
  createDirectoryIfMissing True (root </> directory)
  forM_ (manifest : [root </> directory </> stage </> entry ++ "-audit.json" |
                      stage <- ["pre", "post"], entry <- entries]) $ \path -> do
    present <- doesFileExist path
    when present (removeFile path)
  installed <- prepareInstalledCore root directory
  plugin <- run "plugin-build" [] "bin/build-compiler.sh" []
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        consumer = core </> "OriginalStackDecoder.json"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "bin/export-core.sh" (["-package", "ghc-internal", "-g", "-finfo-table-map"] ++ options ++ [source])
    audits <- forM entries $ \entry -> do
      let report = directory </> stage </> entry ++ "-audit.json"
      result <- try (run (stage ++ "-audit-" ++ entry) [] "python3"
        ["bin/audit-core.py", "--package-manifest", fixturePackages installed, "--entry", entry,
         "--output", report, consumer]) :: IO (Either ExitCode CommandResult)
      pure (report, result)
    pure (stage, consumer, exported, audits)
  let failed = [path | (_,_,_,audits) <- stages, (path, Left _) <- audits]
  unless (null failed) (die ("Original stack decoder strict audits failed: " ++ unwords failed ++
    ". All current reports retained; no success manifest written."))
  let native = directory </> "native"
      executable = native </> "oracle"
  createDirectoryIfMissing True (root </> native)
  compiled <- run "native-compile" [] (fixtureGhc installed)
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-g", "-finfo-table-map",
     "-package", "ghc-internal", "-it/fixtures/compiler", "-odir", native, "-hidir", native,
     driver, "-o", executable]
  observed <- run "native-invariants" [] (root </> executable) []
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  drivers <- listDirectory (root </> "src/driver/THC/Driver")
  let inputs = sort $ [source, driver, "t/haskell-fixtures/StackDecoderFixtures.hs",
        "t/haskell-fixtures/InstalledCoreFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "t/haskell-fixtures/Main.hs", "thc.cabal", "cabal.project", "src/compiler/interface/Main.hs",
        "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "src/driver/cbits/target-layout.c", "bin/export-core.sh", "bin/build-compiler.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> name | name <- pluginFiles, takeExtension name == ".hs"] ++
        ["src/driver/THC/Driver" </> name | name <- drivers, takeExtension name == ".hs"] ++
        ["bin" </> name | name <- scripts, take 5 name == "core_", takeExtension name == ".py"]
      commands = fixtureCommands installed ++ [plugin] ++
        concat [exported : [result | (_, Right result) <- audits] | (_,_,exported,audits) <- stages] ++ [compiled, observed]
      artifacts = fixtureArtifacts installed ++ [executable] ++ concatMap commandArtifacts commands ++
        [path | (_,path,_,_) <- stages] ++ [path | (_,_,_,audits) <- stages, (path,_) <- audits]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["format" .= ("thc-original-stack-decoder-fixture" :: String), "schema" .= (1 :: Int),
     "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "packageManifest" .= fixturePackages installed,
     "stages" .= Map.fromList [(stage,path) | (stage,path,_,_) <- stages],
     "audits" .= [path | (_,_,_,audits) <- stages, (path,_) <- audits],
     "nativeOutput" .= (directory </> "logs/native-invariants.stdout"),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
