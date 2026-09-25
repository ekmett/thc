-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module FileWaitFixtures (prepareFileWait) where

import Control.Exception (try)
import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Exit (ExitCode, die)
import System.FilePath ((</>), takeExtension)
import System.Info (arch, os)

-- Native waitRead#/waitWrite# are unavailable under some I/O managers; the
-- managed descriptor provider has the same explicit Linux x86_64 domain.
prepareFileWait :: FilePath -> IO ()
prepareFileWait root = do
  unless (os == "linux" && arch == "x86_64")
    (die "Original descriptor waits require Linux x86_64 and its native GHC I/O manager")
  let directory = "build/file-wait"
      source = "compiler/test-fixtures/FileWaitAudit.hs"
      driver = "compiler/test-fixtures/FileWaitNative.hs"
      entries = ["waitReadRoot", "waitWriteRoot"]
      binary = directory </> "native/oracle"
      oracle = directory </> "oracle.txt"
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
  createDirectoryIfMissing True (root </> directory </> "native")
  let manifest = root </> directory </> "manifest.json"
  previous <- doesFileExist manifest
  if previous then removeFile manifest else pure ()
  installed <- prepareInstalledCore root directory
  let ghc = fixtureGhc installed
      packagePath = fixturePackages installed
  -- GHC 9.14's raw wait primops use the non-threaded select I/O manager.
  -- Linking the threaded RTS instead aborts before testing either primop.
  nativeBuild <- run "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-package", "ghc-internal", "-package", "unix", "-i./compiler/test-fixtures",
     "-odir", directory </> "native", "-hidir", directory </> "native", driver, "-o", binary]
  nativeRun <- run "native-oracle" [] (root </> binary) []
  unless (commandStdout nativeRun == "read-ready\nwrite-ready\noriginal-bad-fd\n")
    (die "Original descriptor-wait native oracle changed")
  writeFile (root </> oracle) (BS.unpack (commandStdout nativeRun))
  pluginBuild <- run "plugin-build" [] "compiler/build.sh" []
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        output = core </> "FileWaitAudit.json"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source])
    audits <- forM entries $ \entry -> do
      let report = directory </> stage </> entry ++ "-audit.json"
      checked <- try (run (stage ++ "-audit-" ++ entry) [] "python3"
        ["scripts/audit-core.py", "--package-manifest", packagePath,
         "--entry", entry, "--output", report, output]) :: IO (Either ExitCode CommandResult)
      pure (entry, report, checked)
    pure (stage, output, exported, audits)
  let failed = [stage ++ "/" ++ entry | (stage, _, _, audits) <- stages,
                (entry, _, Left _) <- audits]
  unless (null failed) (die ("Original descriptor-wait strict audits failed: " ++ unwords failed))
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/FileWaitFixtures.hs",
        "test/haskell-fixtures/InstalledCoreFixtures.hs", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "compiler/target-layout.c",
        "compiler/export.sh", "compiler/build.sh", "thc.cabal", "scripts/audit-core.py",
        "scripts/core-capabilities.json"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = fixtureCommands installed ++ [nativeBuild, nativeRun, pluginBuild] ++
        concat [exported : [result | (_, _, Right result) <- audits] | (_, _, exported, audits) <- stages]
      artifacts = fixtureArtifacts installed ++ [oracle] ++ concatMap commandArtifacts commands ++
        [output | (_, output, _, _) <- stages] ++
        [report | (_, _, _, audits) <- stages, (_, report, _) <- audits] ++
        [directory </> stage </> "core/THC.InterfaceClosure.json" | stage <- ["pre", "post"]]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "packageManifest" .= packagePath, "stages" .= Map.fromList [(stage, output) | (stage, output, _, _) <- stages],
     "oracle" .= oracle, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "Prepared original descriptor waits: native readiness/bad-FD and strict pre/post installed-Core audits"
