-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module GhcApiFixtures (prepareGhcApi, prepareRecordFields) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value, decodeStrict', object, (.=))
import qualified Data.ByteString.Char8 as BS
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory (canonicalizePath, createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

-- Two independently compiled modules retain duplicate record selectors and a
-- cross-module ordinary selector alias. Both plugin boundaries and subsequent
-- interface hydration must agree on GHC's field-namespace identities.
prepareRecordFields :: FilePath -> IO ()
prepareRecordFields root = do
  let directory = "build/record-fields"
      execute = runLogged 180 root (directory </> "logs")
      modules = ["RecordFieldLibrary", "RecordFieldClient"]
      sources = map (\name -> "compiler/test-fixtures" </> name ++ ".hs") (modules ++ ["RecordFieldNative"])
      single result = case BS.lines (commandStdout result) of
        [value] -> pure (BS.unpack value)
        _ -> die "record-fields: expected one output line"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  plugin <- execute "plugin-build" [] "compiler/build.sh" []
  pluginInfo <- readJson (root </> "build/compiler/plugin.json")
  packageDb <- field pluginInfo "packageDb"
  pluginUnit <- field pluginInfo "unitId"
  helperLocation <- execute "helper-location" [] "cabal" ["list-bin", "exe:thc-interface", "--offline"]
  helper <- single helperLocation
  library <- execute "libdir" [] ghc ["--print-libdir"]
  libdir <- single library
  createDirectoryIfMissing True (root </> directory </> "installed")
  commands <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let output = root </> directory </> stage
        build = output </> "ghc"
    createDirectoryIfMissing True build
    compiled <- execute (stage ++ "-compile") [] ghc
      (["--make", "-O0", "-dynamic-too", "-fforce-recomp", "-fwrite-if-simplified-core",
        "-i", "-icompiler/test-fixtures", "-odir", build, "-hidir", build,
        "-package-db", packageDb, "-plugin-package-id", pluginUnit,
        "-fplugin=THC.Plugin", "-fplugin-opt=THC.Plugin:" ++ output,
        "-o", output </> "oracle"] ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [last sources])
    oracle <- execute (stage ++ "-native") [] (output </> "oracle") []
    recovered <- if stage /= "post" then pure [] else forM modules $ \name -> do
      loaded <- execute ("installed-" ++ name) [] helper
        ["--libdir", libdir, "--unit", "main", "--module", name,
         "--interface", build </> name ++ ".hi", "--home-interfaces", build]
      response <- maybe (die "record-fields: invalid helper JSON") pure (decodeStrict' (commandStdout loaded))
      core <- field response "core" :: IO Value
      writeJson (root </> directory </> "installed" </> name ++ ".json") core
      pure loaded
    pure ([compiled, oracle] ++ recovered)
  audits <- forM [(stage, entry) | stage <- ["pre", "post", "installed"], entry <- ["fieldAlias", "duplicateFields"]] $
    \(stage, entry) -> execute (stage ++ "-audit-" ++ entry) [] "python3"
      (["scripts/audit-core.py", "--entry", "main:RecordFieldClient." ++ entry,
        "--output", directory </> stage </> entry ++ "-audit.json"] ++
       map (\name -> directory </> stage </> name ++ ".json") modules)
  inputs <- hashes root (sources ++ ["compiler/THC/Plugin.hs", "test/haskell-fixtures/GhcApiFixtures.hs"])
  let records = [plugin, helperLocation, library] ++ commands ++ audits
  artifacts <- hashes root (concatMap commandArtifacts records ++
    [directory </> stage </> name ++ ".json" | stage <- ["pre", "post", "installed"], name <- modules])
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "inputHashes" .= inputs, "artifactHashes" .= artifacts,
     "commands" .= map commandRecord records]
  putStrLn "record-fields: original pre/post-Tidy and hydrated Core exported; six strict audits accepted"

-- Ordinary production acquisition of the original compiler package. A failed
-- stage leaves its command record/audit intact and never publishes a success
-- manifest. No replacement compiler bodies or edited package Core are used.
prepareGhcApi :: FilePath -> [String] -> IO ()
prepareGhcApi root requested = do
  let probes = if null requested then ["faststring", "session", "load"] else requested
      fixture = root </> "examples/standard-apps/ghc-api"
      nativeDist = root </> "build/ghc-api/native-control"
      common = "build/ghc-api/logs"
      execute = runLogged 3600 root
  unless (all (`elem` ["faststring", "session", "load"]) probes)
    (die "ghc-api: select faststring, session, or load")
  ghc <- selected "THC_INSTALLED_CORE_GHC" "GHC" "ghc"
  ghcPkg <- selected "THC_INSTALLED_CORE_GHC_PKG" "GHC_PKG" "ghc-pkg"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  source <- lookupEnv "THC_INSTALLED_CORE_GHC_SOURCE"
  runtime <- maybe (pure (root </> "build/install/thc/bin/thc")) canonicalizePath =<< lookupEnv "THC_TEST_RUNTIME"
  suppliedDriver <- lookupEnv "THC_TEST_DRIVER"
  let selection = ["exe:thc", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
  driver <- case suppliedDriver of
    Just path -> canonicalizePath path
    Nothing -> do
      _ <- execute common "driver-build" [] cabal ("build" : selection)
      singleLine "driver" =<< execute common "driver-location" [] cabal ("list-bin" : selection)
  libdir <- singleLine "libdir" =<< execute common "libdir" [] ghc ["--print-libdir"]
  forM_ probes $ \probe -> do
    let name = "ghc-" ++ probe
        acquired = "build/ghc-api/guest-" ++ probe
        logs = acquired </> "logs"
        manifest = root </> acquired </> "manifest.json"
        cabalArguments = [name, "--offline", "--project-file=" ++ (fixture </> "cabal.project"),
          "--builddir=" ++ nativeDist, "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
        arguments = case probe of
          "faststring" -> ["THC λ", "GHC API"]
          "session" -> [libdir]
          _ -> [libdir, fixture </> "subjects/Probe.hs"]
    createDirectoryIfMissing True (root </> acquired)
    stale <- doesFileExist manifest
    when stale (removeFile manifest)
    built <- execute logs "native-build" [] cabal ("build" : cabalArguments)
    located <- execute logs "native-location" [] cabal ("list-bin" : cabalArguments)
    binary <- singleLine "native executable" located
    native <- execute logs "native" [] binary arguments
    managed <- execute logs "thc" [] driver
      (["run", fixture, "--exe", name, "--thc-root", root, "--runtime", runtime,
        "--dist-dir", root </> acquired, "--installed-core", "required",
        "--with-ghc", ghc, "--with-ghc-pkg", ghcPkg] ++
        maybe [] (\path -> ["--ghc-source", path]) source ++ ["--"] ++ arguments)
    unless (commandStdout managed == commandStdout native)
      (die ("ghc-api: " ++ probe ++ " differs from native GHC; see " ++ logs))
    audit <- readJson (root </> acquired </> "audit.json")
    accepted <- field audit "accepted"
    unless accepted (die "ghc-api: production strict audit did not accept")
    packages <- readJson (root </> acquired </> "packages.json")
    records <- field packages "units" :: IO [Value]
    -- The production driver already validates each archive and its provenance.
    -- Retain their content identities in addition to the command/output hashes.
    let commands = [built, located, native, managed]
    inputs <- hashes root (["test/haskell-fixtures/GhcApiFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs"] ++
      ["examples/standard-apps/ghc-api" </> path | path <-
       ["cabal.project", "ghc-api-thc-check.cabal", probe </> "Main.hs", "subjects/Probe.hs"]])
    artifacts <- hashes root ([acquired </> "audit.json", acquired </> "packages.json",
      acquired </> "native/cache/plan.json"] ++ concatMap commandArtifacts commands)
    driverHash <- hashFile driver
    nativeHash <- hashFile binary
    backend <- lookupEnv "THC_BACKEND"
    javaOptions <- lookupEnv "JAVA_TOOL_OPTIONS"
    writeJson manifest $ object
      ["schema" .= (1 :: Int), "probe" .= probe, "strictAccepted" .= True,
       "nativeMatched" .= True, "driver" .= driver, "driverSha256" .= driverHash,
       "nativeExecutable" .= binary, "nativeExecutableSha256" .= nativeHash,
       "runtime" .= runtime, "backendEnvironment" .= backend, "javaToolOptions" .= javaOptions,
       "inputHashes" .= inputs, "artifactHashes" .= artifacts, "packages" .= records,
       "commands" .= map commandRecord commands]
    putStrLn ("ghc-api: " ++ probe ++ " strict-audit accepted and native output matched")
  where
    selected preferred ordinary fallback = do
      override <- lookupEnv preferred
      maybe (maybe fallback id <$> lookupEnv ordinary) pure override
    singleLine description result = case BS.lines (commandStdout result) of
      [value] -> pure (BS.unpack value)
      _ -> die ("ghc-api: expected one " ++ description ++ " line")
