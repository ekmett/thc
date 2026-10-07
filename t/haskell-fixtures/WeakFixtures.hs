-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (082 weak-explicit)
-- Purpose: Check explicit weak-pointer/finalizer operations against native GHC.
-- Consumes: Original WeakAudit, genuine thc:runtime and its GHC dependency CBDs.
-- Produces: Declared runtime support, pre/post CBDs, audits and native observations.
-- Cost: Acquire runtime support once for both stages through the production driver.
--   The deterministic oracle does not establish GC scheduling behavior.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 082.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : WeakFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Original weak Core and its production runtime package prerequisites.
module WeakFixtures (prepareWeakRuntime, prepareWeaks) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (Value(..), object, toJSON, (.=))
import qualified Data.Aeson.KeyMap as KM
import qualified Data.ByteString.Char8 as BS
import Data.List (isPrefixOf, nub, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import FixtureSupport
import InstalledCoreFixtures (field, readJson)
import System.Directory (createDirectoryIfMissing, doesDirectoryExist, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

source, directory, support, supportPackages, supportManifest :: FilePath
source = "t/fixtures/compiler/WeakAudit.hs"
directory = "build/weak-explicit"
support = directory </> "runtime-support"
supportPackages = support </> "packages.json"
supportManifest = support </> "manifest.json"

values :: [Integer]
values = [negate (2 ^ (63 :: Int)), -4097, -1, 0, 1, 42, 4097, 2 ^ (63 :: Int) - 1]

nativeDriver :: String
nativeDriver = unlines
  ["{-# LANGUAGE MagicHash #-}", "module Main where", "import GHC.Exts (Int(I#))",
   "import qualified WeakAudit as P", "emit :: Int -> IO ()",
   "emit input@(I# raw) = putStrLn (show input ++ \"\\t\" ++ show (I# (P.weakComposite raw)))",
   "main :: IO ()", "main = getContents >>= mapM_ (emit . read) . lines"]

-- Source traversal only. Executable artifact inventories come from the producer
-- manifest, never a listing of cache or export directories.
sourceFiles :: FilePath -> FilePath -> IO [FilePath]
sourceFiles root relative = do
  names <- sort <$> listDirectory (root </> relative)
  fmap concat $ forM names $ \name -> do
    let path = relative </> name
    nested <- doesDirectoryExist (root </> path)
    if nested then sourceFiles root path
      else pure [path | takeExtension path `elem` [".hs", ".c", ".h"]]

-- | Acquire the declared real runtime component through the ordinary THC CLI.
-- The graph supplies its built driver; this operation never discovers support
-- from a warmed directory or substitutes an application-owned runtime module.
prepareWeakRuntime :: FilePath -> IO ()
prepareWeakRuntime root = do
  driver <- lookupEnv "THC_FIXTURE_DRIVER" >>= maybe
    (die "weak runtime support requires the declared THC_FIXTURE_DRIVER executable") pure
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  createDirectoryIfMissing True (root </> support)
  old <- doesFileExist (root </> supportManifest)
  when old (removeFile (root </> supportManifest))
  let acquired = support </> "acquisition"
  command <- runLogged 600 root (support </> "logs") "runtime-acquisition"
    [] driver ["build", "thc:lib:runtime", "--project-dir", root,
      "--thc-root", root, "--dist-dir", root </> acquired,
      "--with-ghc", ghc, "--with-ghc-pkg", ghcPkg, "--installed-core", "pinned", "--verify-artifacts"]
  packages <- readJson (root </> acquired </> "packages.json")
  runtime <- field packages "foreignExceptionBridgeUnit" :: IO String
  units <- field packages "units" :: IO [Value]
  owner <- case [unit | unit <- units, ownerId unit == Just runtime] of
    [unit] -> pure unit
    _ -> die "weak support lacks a unique declared runtime unit"
  modules <- field owner "modules" :: IO [Value]
  names <- mapM (`field` "name") modules :: IO [String]
  unless (all (`elem` names) ["THC.Exception", "THC.Internal.Exception", "THC.Internal.Weak"])
    (die "weak support lacks its genuine exception bridge or weak finalizer ABI module")
  retained <- mapM (retainUnitArtifacts root (support </> "modules")) units
  let ready = case packages of
        Object fields -> Object (KM.insert "units" (toJSON (map fst retained)) fields)
        _ -> error "Invalid production package manifest"
      artifacts = concatMap snd retained ++ [supportPackages, support </> "runtime.d"] ++ commandArtifacts command
  writeJson (root </> supportPackages) ready
  runtimeSources <- sourceFiles root "src/runtime"
  inputHashes <- hashes root (runtimeSources ++ ["t/haskell-fixtures/WeakFixtures.hs",
    "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal",
    "cabal.project", "Setup.hs", "src/driver/cbits/target-layout.c",
    "src/main/resources/thc/core-native-overrides.json"])
  -- Ninja consumes these exact declared products as implicit dependencies. Their
  -- deletion/change reruns this owner, including after restoring fixture inputs.
  let escape = concatMap (\c -> case c of
        '$' -> "$$"; '#' -> "\\#"; ' ' -> "\\ "; ':' -> "\\:"; '\\' -> "/"; _ -> [c])
  writeFile (root </> support </> "runtime.d") $ escape (root </> supportManifest) ++ ": " ++
    unwords (map (escape . (root </>)) (concatMap snd retained ++ Map.keys inputHashes)) ++ "\n"
  artifactHashes <- hashes root (sort artifacts)
  driverHash <- hashFile driver
  acquisitionHash <- hashFile (root </> acquired </> "packages.json")
  plan <- readJson (root </> acquired </> "native/cache/plan.json")
  compiler <- field plan "compiler-id" :: IO String
  abi <- field plan "compiler-abi" :: IO String
  unless (compiler == "ghc-9.14.1") (die "weak support selected a different compiler")
  writeJson (root </> supportManifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "runtimeUnit" .= runtime, "packages" .= ready, "driverSha256" .= driverHash,
    "acquisitionSha256" .= acquisitionHash, "compilerAbi" .= abi, "installedCore" .= ("pinned" :: String),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= [commandRecord command]]
  putStrLn "weak runtime: original thc:runtime and declared GHC dependency CBDs acquired"
  where
    ownerId (Object fields) = case KM.lookup "id" fields of Just (String value) -> Just (Text.unpack value); _ -> Nothing
    ownerId _ = Nothing

-- | Compile the original weak source, audit its real runtime closure, and compare
-- explicit operation results with the independent native GHC oracle.
prepareWeaks :: FilePath -> IO ()
prepareWeaks root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
      native = directory </> "native"
      driver = directory </> "NativeWeak.hs"
      requests = native </> "inputs.txt"
      oracle = directory </> "oracle.tsv"
      executable = native </> "weak-oracle"
      execute = runLogged 600 root (directory </> "logs")
  supportReceipt <- readJson (root </> supportManifest)
  runtime <- field supportReceipt "runtimeUnit" :: IO String
  supportHashes <- field supportReceipt "artifactHashes" :: IO (Map.Map FilePath String)
  forM_ (Map.toList supportHashes) $ \(path, expected) -> do
    actual <- hashFile (root </> path)
    unless (actual == expected) (die "Stale declared weak runtime artifact")
  createDirectoryIfMissing True (root </> native)
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Explicit weak fixture requires GHC 9.14.1")
  writeFile (root </> driver) nativeDriver
  writeFile (root </> requests) (unlines (map show values))
  built <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "t/fixtures/compiler"), "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", root </> executable]
  observed <- runLoggedWithInput requests 30 root (directory </> "logs") "native-oracle" []
    (root </> executable) []
  let observations = BS.unpack (commandStdout observed)
      rows = map words (lines observations)
      parsed = traverse (\fields -> case fields of
        [input, result] -> (,) <$> readInteger input <*> readInteger result
        _ -> Nothing) rows
      signed n = (n + 2 ^ (63 :: Int)) `mod` 2 ^ (64 :: Int) - 2 ^ (63 :: Int)
  unless (parsed == Just [(input, signed (input + 58)) | input <- values]) $
    die ("Native explicit weak contract mismatch: " ++ observations)
  writeFile (root </> oracle) observations
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "WeakAudit.cbd", '@' : supportPackages]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=weakComposite", source])
    audited <- execute (stage ++ "-audit") [] "python3" ["bin/audit-core.py", "--package-manifest", supportPackages,
      "--entry", "main:WeakAudit.weakComposite", "--entry", runtime ++ ":THC.Internal.Weak.runWeakFinalizer",
      "--output", stageDir </> "audit.json", core </> "WeakAudit.cbd"]
    pure (stage, modules, [exported, audited])
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let inputs = sort $ [source, "t/haskell-fixtures/WeakFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "t/haskell-fixtures/Main.hs", "thc.cabal", "bin/core-capabilities.json", "bin/audit-core.py",
        "src/main/resources/thc/scalar-primop-signatures.json", "bin/plugin.py",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh"] ++
        ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
        ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      commands = [version, built, observed] ++ concat [runs | (_, _, runs) <- stages]
      artifacts = [driver, requests, oracle, executable, supportManifest] ++ Map.keys supportHashes ++
        concat [filter (not . isPrefixOf "@") modules ++ [directory </> stage </> "audit.json"]
          | (stage, modules, _) <- stages] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root (sort (nub artifacts))
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entry" .= ("weakComposite" :: String), "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
    "runtimeSupport" .= supportManifest, "runtimeUnit" .= runtime, "nativeRows" .= length rows,
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn ("weak-explicit: " ++ show (length rows) ++ " native observations, original runtime closure, pre/post strict audits; no GC timing oracle")
