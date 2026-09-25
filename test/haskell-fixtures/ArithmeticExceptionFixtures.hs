-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module ArithmeticExceptionFixtures (prepareArithmeticExceptions, refreshArithmeticCore) where

import Control.Exception (try)
import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (CommandResult(..), hashFile, hashes, runLogged, writeJson)
import qualified THC.Driver.Installed as Installed
import InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore, field, readJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Exit (ExitCode, die)
import System.FilePath ((</>), takeExtension)

prepareArithmeticExceptions :: FilePath -> IO ()
prepareArithmeticExceptions = prepare False

-- Refresh exports/audits independently of the already established native oracle.
-- This writes no complete fixture manifest and makes no new native/JVM claim.
refreshArithmeticCore :: FilePath -> IO ()
refreshArithmeticCore = prepare True

prepare :: Bool -> FilePath -> IO ()
prepare coreOnly root = do
  let directory = "build/arithmetic-exceptions"
      source = "compiler/test-fixtures/ArithmeticExceptionsAudit.hs"
      driver = "compiler/test-fixtures/ArithmeticExceptionsNative.hs"
      entries = ["scalarDivZero", "scalarOverflow", "scalarUnderflow", "tupleDivZero", "tupleOverflow", "tupleUnderflow"]
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
  createDirectoryIfMissing True (root </> directory </> "installed/bundles")
  -- A failed refresh must never leave a prior success receipt consumable. The
  -- independent native receipt survives: --core-only neither rebuilds nor
  -- certifies that oracle, and a later full run revalidates its exact hashes.
  forM_ (["core-manifest.json", "manifest.json"] ++
         [stage </> entry ++ "-audit.json" | stage <- ["pre", "post"], entry <- entries]) $ \name -> do
    let path = root </> directory </> name
    exists <- doesFileExist path
    when exists (removeFile path)
  installed <- prepareInstalledCore root directory
  let ghc = fixtureGhc installed
      packagePath = fixturePackages installed
  pluginBuild <- run "plugin-build" [] "compiler/build.sh" []
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
        consumer = core </> "ArithmeticExceptionsAudit.json"
    mapM_ (createDirectoryIfMissing True . (root </>)) [core, ghcOut]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ [source])
    audits <- forM entries $ \entry -> do
      result <- try (run (stage ++ "-audit-" ++ entry) [] "python3"
        ["scripts/audit-core.py", "--package-manifest", packagePath, "--entry", entry,
         "--output", directory </> stage </> entry ++ "-audit.json", consumer]) :: IO (Either ExitCode CommandResult)
      pure (entry, result)
    pure (stage, consumer, exported, audits)
  let failed = [stage ++ "/" ++ entry | (stage, _, _, audits) <- stages, (entry, Left _) <- audits]
  unless (null failed) (die ("Arithmetic strict audits failed: " ++ unwords failed ++
    ". Reports and command logs retained in " ++ directory ++ "; no success manifest written."))
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  drivers <- listDirectory (root </> "src/THC/Driver")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/ArithmeticExceptionFixtures.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/InstalledCoreFixtures.hs",
        "thc.cabal", "cabal.project", "compiler/interface/Main.hs", "scripts/audit-core.py", "scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json", "compiler/target-layout.c",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["src/THC/Driver" </> file | file <- drivers, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = fixtureCommands installed ++ [pluginBuild] ++
        concat [exported : [result | (_, Right result) <- audits] | (_,_,exported,audits) <- stages]
      artifacts = fixtureArtifacts installed ++ concatMap commandArtifacts commands ++
        [consumer | (_,consumer,_,_) <- stages] ++
        [directory </> stage </> "core/THC.InterfaceClosure.json" | stage <- ["pre", "post"]] ++
        [directory </> stage </> entry ++ "-audit.json" | stage <- ["pre", "post"], entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  let record extra = object (["schema" .= (2 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
        "packageManifest" .= packagePath, "stages" .= Map.fromList [(stage, consumer) | (stage, consumer, _, _) <- stages],
        "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
        "commands" .= map commandRecord commands, "runtimeVerified" .= False] ++ extra)
  writeJson (root </> directory </> "core-manifest.json") (record ["coreOnly" .= True])
  unless coreOnly $ do
    let native = directory </> "native"
        binary = native </> "oracle"
        receipt = native </> "receipt.json"
        nativeArtifacts = [binary, directory </> "logs/native-oracle.stdout", directory </> "logs/native-oracle.stderr",
          directory </> "logs/native-oracle.command.json", directory </> "logs/native-compile.stdout",
          directory </> "logs/native-compile.stderr", directory </> "logs/native-compile.command.json"]
        compiler = object ["selection" .= Installed.installedCompiler (fixtureContext installed),
          "libdir" .= Installed.installedLibdir (fixtureContext installed),
          "registration" .= fixtureRegistration installed]
        compileArguments = ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
          "-package", "ghc-internal", "-i./compiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", binary]
    nativeInputs <- hashes root [source, driver]
    exists <- doesFileExist (root </> receipt)
    reusable <- if not exists then pure False else do
      previous <- readJson (root </> receipt)
      oldInputs <- field previous "inputHashes"
      oldCompiler <- field previous "compiler"
      oldArguments <- field previous "compileArguments"
      oldArtifacts <- field previous "artifactHashes" :: IO (Map.Map FilePath String)
      present <- and <$> mapM (doesFileExist . (root </>)) nativeArtifacts
      if oldInputs /= nativeInputs || oldCompiler /= compiler || oldArguments /= compileArguments ||
         not present || Map.keys oldArtifacts /= sort nativeArtifacts
        then pure False else (== oldArtifacts) <$> hashes root nativeArtifacts
    unless reusable $ do
      createDirectoryIfMissing True (root </> native)
      _ <- run "native-compile" [] ghc compileArguments
      observed <- run "native-oracle" [] (root </> binary) []
      unless (length (BS.lines (commandStdout observed)) == 42) (die "Arithmetic exception oracle row count changed")
      observedHashes <- hashes root nativeArtifacts
      writeJson (root </> receipt) $ object ["inputHashes" .= nativeInputs, "compiler" .= compiler,
        "compileArguments" .= compileArguments,
        "artifactHashes" .= observedHashes]
    nativeHashes <- hashes root (receipt : nativeArtifacts)
    writeJson (root </> directory </> "manifest.json") (record
      ["oracle" .= (directory </> "logs/native-oracle.stdout"),
       "native" .= object ["artifactHashes" .= nativeHashes]])
  putStrLn (if coreOnly then "Prepared arithmetic Core only: complete installed originals and twelve strict audits; no native/JVM run"
    else "Prepared arithmetic exceptions: complete installed originals, twelve strict audits and verified 42-row native oracle")
