-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module BoundThreadQueryFixtures (prepareBoundThreadQuery) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecode, object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import qualified Data.ByteString.Lazy as BL
import Data.Foldable (toList)
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
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

-- Inspect genuine exported applications, never substitute or reconstruct FCallIds.
foreignCalls :: Value -> [Value]
foreignCalls (Array xs) = (case toList xs of
  [String "app", _, _, _, _, _, Object metadata] | KeyMap.member "foreignCall" metadata -> [Array xs]
  _ -> []) ++ concatMap foreignCalls (toList xs)
foreignCalls (Object xs) = concatMap foreignCalls (KeyMap.elems xs)
foreignCalls _ = []

prepareBoundThreadQuery :: FilePath -> IO ()
prepareBoundThreadQuery root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
      execute label = runLogged 180 root (directory </> "logs") label
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original bound-thread query requires GHC 9.14.1")
  libdir <- execute "ghc-libdir" [] ghc ["--print-libdir"]
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
        modules = [core </> "BoundThreadQueryAudit.json", core </> "THC.InterfaceClosure.json"]
    exported <- execute (stage ++ "-export") [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=boundThreadQuery", source])
    actual <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (actual == ["BoundThreadQueryAudit.json", "THC.InterfaceClosure.json"])
      (die ("Unexpected bound-thread query module inventory: " ++ show actual))
    documents <- mapM (readJson . (root </>)) modules
    let calls = concatMap foreignCalls documents
    unless (not (null calls) && all original calls)
      (die "Original imported rtsSupportsBoundThreads FCallId/certificate missing; full installed Core may be required")
    audited <- execute (stage ++ "-audit") [] "python3" (["scripts/audit-core.py", "--entry", "boundThreadQuery",
      "--output", stageDir </> "audit.json"] ++ modules)
    report <- readJson (root </> stageDir </> "audit.json")
    case report of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
        KeyMap.lookup "issues" fields == Just (Array mempty),
        KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
      _ -> die "Bound-thread query original closure did not pass strict audit"
    pure (stage, modules, stageDir </> "audit.json", commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, nativeSource, "test/haskell-fixtures/BoundThreadQueryFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
        "scripts/core-capabilities.json", "scripts/audit-core.py", "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/plugin.py", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh"] ++
        ["compiler/THC" </> name | name <- plugins, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      artifacts = commandArtifacts version ++ commandArtifacts libdir ++
        concat [oracle:logs | (_, _, oracle, logs) <- nativeRuns] ++
        concat [modules ++ audit:logs | (_, modules, audit, logs) <- stages]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "ghcLibdir" .= BS.unpack (commandStdout libdir), "producerRoot" .= root,
    "installedArtifactsHashed" .= False, "entry" .= ("boundThreadQuery" :: String),
    "nativeRowsPerMode" .= length values, "nativeControls" .= Map.fromList [(mode, flags) | (mode, flags, _, _) <- nativeRuns],
    "stages" .= Map.fromList [(stage, modules) | (stage, modules, _, _) <- stages],
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "bound-thread-query: original import, 8 rows in each native RTS mode, pre/post strict closure; negative THC capability only"
  where
    original (Array xs) = case toList xs of
      [String "app", Array headParts, _, _, _, _, Object metadata] ->
        case (toList headParts, KeyMap.lookup "foreignCall" metadata) of
          ([String "var", String identity, _], Just (Object descriptor)) ->
            not (Text.null identity) && case KeyMap.lookup "target" descriptor of
              Just (Object target) -> KeyMap.lookup "symbol" target == Just (String "rtsSupportsBoundThreads") &&
                KeyMap.lookup "unit" target == Just (String "ghc-internal")
              _ -> False
          _ -> False
      _ -> False
    original _ = False
