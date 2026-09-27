-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings, LambdaCase #-}

-- |
-- Module      : TupleJoinFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for tuple join.
module TupleJoinFixtures (prepareTupleJoins) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (sort)
import qualified Data.Text as Text
import FixtureSupport
import InstalledCoreFixtures (InstalledFixture(..), prepareInstalledCore, field)
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension, takeDirectory)

-- Reuse a hash-checked production bundle for the exact two boot owners when
-- supplied. No compiler re-export and no editing/filtering of module bodies.
installedPackages :: FilePath -> FilePath -> IO (FilePath, [FilePath])
installedPackages root directory = lookupEnv "THC_TUPLE_JOIN_PACKAGES" >>= \case
  Nothing -> do
    installed <- prepareInstalledCore root directory
    pure (fixturePackages installed, fixtureArtifacts installed)
  Just source -> do
    document <- readJson source
    units <- field document "units" :: IO [Value]
    selected <- fmap concat $ forM units $ \unit -> do
      identifier <- field unit "id" :: IO String
      if identifier == "ghc-internal" || take 13 identifier == "ghc-internal-" || take 4 identifier == "rts-"
        then pure [unit] else pure []
    unless (length selected >= 2) (die "Reuse manifest lacks original ghc-internal/rts records")
    createDirectoryIfMissing True (root </> directory </> "installed/bundles")
    copied <- forM selected $ \unit -> case unit of
      Object fields | Just bundle <- KeyMap.lookup "bundle" fields -> do
        identifier <- field unit "id" :: IO String
        original <- field bundle "path"
        expected <- field bundle "sha256"
        let input = takeDirectory source </> original
            relative = directory </> "installed/bundles" </> identifier ++ ".zip"
        actual <- hashFile input
        unless (actual == expected) (die "Reused installed bundle hash mismatch")
        copyFile input (root </> relative)
        let replacement = object ["path" .= (root </> relative), "sha256" .= actual]
        pure (Object (KeyMap.insert "bundle" replacement fields), [relative])
      _ -> pure (unit, [])
    let manifest = directory </> "installed/packages.json"
    writeJson (root </> manifest) $ object ["format" .= ("thc-core-packages" :: String),
      "schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "units" .= map fst copied]
    pure (manifest, manifest : concatMap snd copied)

walk :: Value -> [Value]
walk value = value : case value of
  Object fields -> concatMap walk (KeyMap.elems fields)
  Array fields -> concatMap walk (toList fields)
  _ -> []

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

prepareTupleJoins :: Bool -> FilePath -> IO ()
prepareTupleJoins originalLibrary root = do
  let directory = "build/tuple-join-input"
      output = root </> directory
      source = "compiler/test-fixtures/TupleJoinInputAudit.hs"
      driver = "compiler/test-fixtures/TupleJoinInputAuditNative.hs"
      entries = ["forward", "recursiveSwap", "nested"] ++
        [entry | originalLibrary, entry <- ["originalRoundTo", "emptyRetry"]] ++ ["emptyException"] :: [String]
      logs = directory </> "commands"
      native = directory </> "native"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Tuple joins require GHC 9.14.1")
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-package", "ghc-internal",
     "-icompiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observed <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  unless (length (BSC.lines (commandStdout observed)) == 222) (die "Unexpected tuple-join oracle row count")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  (packages, packageArtifacts) <- if originalLibrary then installedPackages root directory else pure ("", [])
  artifacts <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        report = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ ["-package", "ghc-internal", source])
    modules <- sort . filter (== "TupleJoinInputAudit.json") <$> listDirectory (root </> core)
    let paths = map (core </>) modules
    nodes <- concatMap walk <$> mapM (readJson . (root </>)) paths
    let tupleJoins = [() | Object fields <- nodes, KeyMap.member "joinValueArity" fields,
          Just (Array rhs) <- [KeyMap.lookup "expr" fields], String "lam" : Array parameters : _ <- [toList rhs],
          Object parameter <- toList parameters, Just (Object proof) <- [KeyMap.lookup "rep" parameter],
          KeyMap.lookup "aggregate" proof == Just (String "unboxed-tuple"),
          Just (Array registers) <- [KeyMap.lookup "primReps" proof], not (null (toList registers))]
        emptyCases = [() | Array fields <- nodes, String "case" : _ : _ : Array alternatives : Object metadata : _ <- [toList fields],
          null (toList alternatives), Just (Object binder) <- [KeyMap.lookup "binder" metadata],
          Just (Object proof) <- [KeyMap.lookup "rep" binder], KeyMap.lookup "aggregate" proof == Just (String "unboxed-tuple")]
        original = [() | Array fields <- nodes, String "var" : String name : _ <- [toList fields],
          "GHC.Internal.Float.$wroundTo" `Text.isSuffixOf` name]
    unless (length tupleJoins >= 3 && length emptyCases >= 2 && not (null original))
      (die ("Tuple fixture lost original roundTo/tuple joins/empty cases: " ++ show (length tupleJoins, length emptyCases, length original)))
    audited <- runLogged 180 root logs (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py", "--output", report] ++ [argument | originalLibrary, argument <- ["--package-manifest", packages]] ++
       concatMap (\entry -> ["--entry", "main:TupleJoinInputAudit." ++ entry]) entries ++ paths)
    result <- readJson (root </> report)
    case result of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
      _ -> die ("Strict tuple-join audit rejected " ++ stage)
    pure (report : paths ++ commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputs <- hashes root $ sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
    "test/haskell-fixtures/TupleJoinFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/InstalledCoreFixtures.hs",
    "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  outputs <- hashes root $ [directory </> "oracle.tsv", native </> "oracle"] ++ packageArtifacts ++ artifacts ++
    concatMap commandArtifacts [version, compiled, observed]
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "nativeRows" .= (222 :: Int), "originalLibrary" .= originalLibrary,
    "packageManifest" .= packages, "inputHashes" .= inputs, "artifactHashes" .= outputs]
  putStrLn ("tuple-join: 222 native rows, " ++ show (length entries) ++ " guest roots, original roundTo=" ++
    show originalLibrary ++ "; pre/post strict audits accepted")
