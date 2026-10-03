-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (145 aggregate-heap)
-- Purpose: Check aggregate heap fields retain payload layout and lazy sharing.
-- Produces/consumed result: pre/post CBD closure, oracle.tsv and constructor facts.
-- Cost and overlap: Heap fields differ from call/result transport; retain missing
--   cases in one aggregate corpus. A separate receipt/audit framework is excessive.
-- Build status: QUARANTINED. Producer and consumer glob stage CBD directories.
--   --export-only still builds/runs native code; it only omits strict audits.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 145.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : AggregateHeapFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for aggregate heap.
module AggregateHeapFixtures (prepareAggregateHeap) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (intercalate, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Text as Text
import FixtureSupport
import THC.Compact.Module (readModuleValue)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

entries :: [String]
entries = ["boxedTag", "mixedTuple", "nestedTuple", "emptyTuple", "sumTuple",
  "sumIgnoreLazy", "sharedLazy", "floatingEdges", "originalBoxedTag"]

inputs :: [Integer]
inputs = [-9223372036854775808, -2147483649, -2147483648, -5, -1,
  0, 1, 2, 3, 4, 5, 6, 9223372036854775807]

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

readCore :: FilePath -> IO Value
readCore path = BS.readFile path >>= either die pure . readModuleValue

-- Check the shape actually exported by GHC, including zero-width components.
-- The retained full records include the physical primReps and sum slot maps.
representationShape :: Value -> String
representationShape (Object fields) = case KeyMap.lookup "aggregate" fields of
  Just (String "unboxed-tuple") -> children "tuple" "components"
  Just (String "unboxed-sum") -> children "sum" "alternatives"
  _ -> "leaf"
  where
    children name key = case KeyMap.lookup key fields of
      Just (Array values) -> name ++ "(" ++ intercalate "," (map representationShape (toList values)) ++ ")"
      _ -> "missing-components"
representationShape _ = "missing-representation"

constructorProofs :: String -> Value -> IO [Value]
constructorProofs ghcUnit (Object moduleFields) = case KeyMap.lookup "constructors" moduleFields of
  Just (Array constructors) -> forM expected $ \(name, shapes) -> do
    fields <- case [fields | Object fields <- toList constructors,
      KeyMap.lookup "id" fields == Just (String (Text.pack (if name == "BoxedRep" then ghcUnit ++ ":GHC.Core.TyCon.BoxedRep" else "main:AggregateHeapFields." ++ name)))] of
      [fields] -> pure fields
      _ -> die ("aggregate-heap: missing or ambiguous constructor " ++ name)
    unless (KeyMap.lookup "kind" fields == Just (String "boxed"))
      (die ("aggregate-heap: constructor is not boxed: " ++ name))
    case KeyMap.lookup "fieldTypes" fields of
      Just (Array types) | map representationShape (toList types) == shapes -> pure ()
      actual -> die ("aggregate-heap: changed worker field shape for " ++ name ++ ": " ++ show actual)
    pure (Object fields)
  _ -> die "aggregate-heap: missing constructor metadata"
  where
    expected = [("Boxed", ["sum(tuple(),leaf)"]),
      ("Mixed", ["leaf", "tuple(leaf,leaf,leaf)", "leaf"]),
      ("Nested", ["leaf", "tuple(tuple(leaf,leaf),leaf)", "leaf"]),
      ("Empty", ["leaf", "tuple()", "leaf"]),
      ("Sum", ["leaf", "sum(tuple(),tuple(leaf,leaf,leaf))", "leaf"]),
      ("Shared", ["leaf", "tuple(leaf,leaf)", "leaf"]),
      ("BoxedRep", ["sum(tuple(),leaf)"])]
constructorProofs _ _ = die "aggregate-heap: malformed Core module"

prepareAggregateHeap :: FilePath -> Bool -> IO ()
prepareAggregateHeap root exportOnly = do
  let directory = "build/aggregate-heap"
      output = root </> directory
      source = "t/fixtures/compiler/AggregateHeapFields.hs"
      driver = "t/fixtures/compiler/AggregateHeapFieldsNative.hs"
      native = directory </> "native"
      binary = native </> "oracle"
      logs = directory </> "commands"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  ghcPkg <- maybe "ghc-pkg" id <$> lookupEnv "GHC_PKG"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Aggregate heap fields require GHC 9.14.1")
  packageId <- runLogged 30 root logs "ghc-package-id" [] ghcPkg ["field", "ghc", "id", "--simple-output"]
  ghcUnit <- case words (BSC.unpack (commandStdout packageId)) of
    [unit] -> pure unit
    _ -> die "aggregate-heap: expected one selected GHC package identity"
  info <- runLogged 30 root logs "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String, String)] of
    Just fields | lookup "target word size" fields == Just "8",
      Just host <- lookup "Host platform" fields,
      Just target <- lookup "Target platform" fields, host == target -> pure ()
    _ -> die "Aggregate heap fields require native 64-bit GHC"
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-package", "ghc", "-it/fixtures/compiler", "-odir", native, "-hidir", native,
     driver, "-o", binary]
  observations <- runLogged 30 root logs "native-run" [] (root </> binary) []
  let parse line = case splitTab line of
        [name, input, result] -> (,,) name <$> readInteger input <*> readInteger result
        _ -> Nothing
  rows <- maybe (die "aggregate-heap: malformed native oracle") pure
    (traverse parse (lines (BSC.unpack (commandStdout observations))))
  unless ([(name, input) | (name, input, _) <- rows] == [(name, input) | name <- entries, input <- inputs])
    (die "aggregate-heap: native oracle omitted, reordered, or duplicated inputs")
  unless ([result | ("boxedTag", _, result) <- rows] == [result | ("originalBoxedTag", _, result) <- rows])
    (die "aggregate-heap: source analogue differs from original GHC BoxedRep")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observations)
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modulePath = core </> "AggregateHeapFields.cbd"
        report = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ ["-package", "ghc", source])
    proof <- constructorProofs ghcUnit =<< readCore (root </> modulePath)
    modules <- sort . filter ((== ".cbd") . takeExtension) <$> listDirectory (root </> core)
    let modulePaths = map (core </>) modules
    audits <- if exportOnly then pure [] else do
      audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
        (["bin/audit-core.py", "--output", report] ++
          concatMap (\entry -> ["--entry", "main:AggregateHeapFields." ++ entry]) entries ++ modulePaths)
      result <- readJson (root </> report)
      case result of
        Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
          KeyMap.lookup "issues" fields == Just (Array mempty),
          KeyMap.lookup "missingGlobals" fields == Just (Array mempty) -> pure ()
        _ -> die ("aggregate-heap: strict audit rejected " ++ stage)
      pure (report : commandArtifacts audited)
    pure (stage, proof, modulePaths ++ audits ++ commandArtifacts exported)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root $ sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/AggregateHeapFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  artifactHashes <- hashes root $ [directory </> "oracle.tsv", binary] ++
    concat [paths | (_, _, paths) <- stages] ++ concatMap commandArtifacts [version, packageId, info, compiled, observations]
  writeJson (if exportOnly then output </> "export-manifest.json" else manifest) $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "ghcUnit" .= ghcUnit, "wordBits" .= (64 :: Int),
     "entries" .= entries, "inputs" .= inputs, "nativeRows" .= length rows,
     "strictAccepted" .= not exportOnly,
     "constructorProofs" .= Map.fromList [(stage, proof) | (stage, proof, _) <- stages],
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("aggregate-heap: " ++ show (length rows) ++ " native rows and genuine pre/post Core; " ++
    if exportOnly then "strict audit deferred (--export-only)" else "strict audits accepted")
