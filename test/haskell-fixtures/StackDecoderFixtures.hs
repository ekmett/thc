-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
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
      source = "compiler/test-fixtures/OriginalStackDecoder.hs"
      driver = "compiler/test-fixtures/OriginalStackDecoderNative.hs"
      entries = ["captureNamed", "observeSnapshot"]
      run label env program args = runLogged 300 root (directory </> "logs") label env program args
  createDirectoryIfMissing True (root </> directory)
  forM_ (manifest : [root </> directory </> stage </> entry ++ "-audit.json" |
                      stage <- ["pre", "post"], entry <- entries]) $ \path -> do
    present <- doesFileExist path
    when present (removeFile path)
  installed <- prepareInstalledCore root directory
  plugin <- run "plugin-build" [] "compiler/build.sh" []
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        consumer = core </> "OriginalStackDecoder.json"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (["-package", "ghc-internal", "-g", "-finfo-table-map"] ++ options ++ [source])
    audits <- forM entries $ \entry -> do
      let report = directory </> stage </> entry ++ "-audit.json"
      result <- try (run (stage ++ "-audit-" ++ entry) [] "python3"
        ["scripts/audit-core.py", "--package-manifest", fixturePackages installed, "--entry", entry,
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
     "-package", "ghc-internal", "-icompiler/test-fixtures", "-odir", native, "-hidir", native,
     driver, "-o", executable]
  observed <- run "native-invariants" [] (root </> executable) []
  pluginFiles <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  drivers <- listDirectory (root </> "src/THC/Driver")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/StackDecoderFixtures.hs",
        "test/haskell-fixtures/InstalledCoreFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs", "thc.cabal", "cabal.project", "compiler/interface/Main.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/target-layout.c", "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> name | name <- pluginFiles, takeExtension name == ".hs"] ++
        ["src/THC/Driver" </> name | name <- drivers, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts, take 5 name == "core_", takeExtension name == ".py"]
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
