-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module BoundThreadQueryFixtures (prepareBoundThreadQuery) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), FromJSON, Result(..), fromJSON, eitherDecode, object, (.=))
import qualified Data.Aeson.Key as Key
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as BL
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (CommandResult(..), hashFile, hashes, runLogged, writeJson)
import qualified THC.Driver.Cache as Cache
import qualified THC.Driver.Installed as Installed
import qualified THC.Driver.Project as Project
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (getExecutablePath, lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

directory, source, nativeSource :: FilePath
directory = "build/bound-thread-query"
source = "compiler/test-fixtures/BoundThreadQueryAudit.hs"
nativeSource = "compiler/test-fixtures/BoundThreadQueryNative.hs"

values :: [Integer]
values = [negate (2 ^ (63 :: Int)), -4097, -1, 0, 1, 42, 4097, 2 ^ (63 :: Int) - 1]

readJson :: FilePath -> IO Value
readJson path = either die pure . eitherDecode =<< BL.readFile path

field :: FromJSON a => Value -> String -> IO a
field (Object fields) name = case KeyMap.lookup (Key.fromString name) fields of
  Just value -> case fromJSON value of Success result -> pure result; Error _ -> bad
  Nothing -> bad
  where bad = die ("Invalid bound-thread query fixture field: " ++ name)
field _ name = die ("Invalid bound-thread query fixture record: " ++ name)

prepareBoundThreadQuery :: FilePath -> IO ()
prepareBoundThreadQuery root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
      execute label = runLogged 180 root (directory </> "logs") label
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  cabal <- maybe "cabal" id <$> lookupEnv "CABAL"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original bound-thread query requires GHC 9.14.1")
  libdir <- execute "ghc-libdir" [] ghc ["--print-libdir"]
  -- Use the production selected-installed provider, not thin interface fragments
  -- or a pinned source overlay. See ArithmeticExceptionFixtures' same provider.
  let selection = ["exe:thc-interface", "--offline", "--with-compiler=" ++ ghc, "--with-hc-pkg=" ++ ghcPkg]
      packagePath = directory </> "installed/packages.json"
  helperBuild <- runLogged 600 root (directory </> "logs") "helper-build" [] cabal ("build" : selection)
  helperLocation <- execute "helper-location" [] cabal ("list-bin" : selection)
  helper <- case lines (BS.unpack (commandStdout helperLocation)) of
    [path] -> pure path
    _ -> die "Expected one selected-GHC thc-interface executable"
  plan <- readJson (root </> "dist-newstyle/cache/plan.json")
  compilerId <- field plan "compiler-id" :: IO String
  abi <- field plan "compiler-abi" :: IO String
  arch <- field plan "arch" :: IO String
  os <- field plan "os" :: IO String
  unless (compilerId == "ghc-9.14.1") (die "Bound-thread query helper selected a different compiler")
  selected <- Installed.installedContext ghc ghcPkg helper [] (object
    ["id" .= compilerId, "abi" .= abi, "platform" .= (arch ++ "-" ++ os),
     "way" .= ("dynamic-nonprofiling" :: String)])
  registration <- execute "ghc-internal-unit" [] ghcPkg
    ["--global", "--no-user-package-db", "field", "ghc-internal", "id", "--simple-output"]
  internal <- case BS.words (commandStdout registration) of
    [name] -> pure (BS.unpack name)
    _ -> die "Expected one selected ghc-internal registration"
  let discover seen [] = pure (reverse seen)
      discover seen (identifier:todo)
        | identifier `elem` map Installed.registeredId seen = discover seen todo
        | otherwise = do
            unit <- Installed.discoverInstalled selected identifier
            discover (unit:seen) (Installed.installedDepends unit ++ todo)
  units <- discover [] [internal]
  Installed.validateReexports units
  cache <- Cache.coreCacheDirectory
  driverHash <- hashFile =<< getExecutablePath
  createDirectoryIfMissing True (root </> directory </> "installed/bundles")
  bundles <- forM units $ \unit -> do
    acquired <- Project.prepareInstalledBundle cache (root </> directory </> "installed/staging")
      (root </> "compiler/target-layout.c") driverHash selected unit
    original <- case acquired of
      Right value -> pure value
      Left missing -> die ("Bound-thread query requires complete-interface-core from the selected GHC: " ++
        Installed.missingUnit missing ++ ":" ++ Installed.missingModule missing ++
        " (" ++ Installed.missingInterface missing ++ "). Thin/pinned source is not a substitute.")
    let bundle = Project.installedBundle original
        destination = directory </> "installed/bundles" </> Installed.registeredId unit ++ ".zip"
    copyFile (Project.bundlePath bundle) (root </> destination)
    digest <- hashFile (root </> destination)
    unless (digest == Project.bundleHash bundle) (die "Installed bound-thread query bundle changed while copying")
    let local = original {Project.installedBundle = bundle {Project.bundlePath = root </> destination}}
    pure (Project.installedRecords unit local, destination)
  writeJson (root </> packagePath) $ object
    ["format" .= ("thc-core-packages" :: String), "schema" .= (1 :: Int),
     "ghc" .= ("9.14.1" :: String), "units" .= concatMap fst bundles]
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
    audited <- execute (stage ++ "-audit") [] "python3" (["scripts/audit-core.py", "--package-manifest", packagePath, "--entry", "boundThreadQuery",
      "--output", stageDir </> "audit.json"] ++ modules)
    report <- readJson (root </> stageDir </> "audit.json")
    case report of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
        KeyMap.lookup "issues" fields == Just (Array mempty),
        KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
      _ -> die "Bound-thread query original closure did not pass strict audit"
    calls <- field report "foreignCalls" :: IO [Value]
    symbols <- mapM (`field` "symbol") calls :: IO [String]
    unless (not (null symbols) && all (== "rtsSupportsBoundThreads") symbols)
      (die "Original installed bound-thread query missing or unexpected foreign obligation")
    pure (stage, modules, stageDir </> "audit.json", commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "compiler/THC")
  drivers <- listDirectory (root </> "src/THC/Driver")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, nativeSource, "test/haskell-fixtures/BoundThreadQueryFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal", "cabal.project",
        "compiler/interface/Main.hs", "compiler/target-layout.c",
        "scripts/core-capabilities.json", "scripts/audit-core.py", "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/plugin.py", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh"] ++
        ["compiler/THC" </> name | name <- plugins, takeExtension name == ".hs"] ++
        ["src/THC/Driver" </> name | name <- drivers, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      artifacts = packagePath : map snd bundles ++ concatMap commandArtifacts [version, libdir, helperBuild, helperLocation, registration] ++
        concat [oracle:logs | (_, _, oracle, logs) <- nativeRuns] ++
        concat [modules ++ audit:logs | (_, modules, audit, logs) <- stages] ++
        [directory </> stage </> "core/THC.InterfaceClosure.json" | stage <- ["pre", "post"]]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "ghcLibdir" .= BS.unpack (commandStdout libdir), "producerRoot" .= root,
    "installedArtifactsHashed" .= False, "entry" .= ("boundThreadQuery" :: String),
    "packageManifest" .= packagePath, "installedCompiler" .= Installed.installedCompiler selected,
    "nativeRowsPerMode" .= length values, "nativeControls" .= Map.fromList [(mode, flags) | (mode, flags, _, _) <- nativeRuns],
    "stages" .= Map.fromList [(stage, modules) | (stage, modules, _, _) <- stages],
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "bound-thread-query: original import, 8 rows in each native RTS mode, pre/post strict closure; negative THC capability only"
