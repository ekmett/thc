-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (150 sum-input)
-- Purpose: Check sum arguments through direct calls, PAPs, capture, tail calls,
--   overapplication, empty payloads and escaped references without leaking handoffs.
-- Consumes: SumInputAudit{,Native}.hs, selected GHC, exporter/plugin and auditor.
-- Produces/consumed result: Named pre/post CBDs, 333 native rows, audits and manifest.
-- Cost and overlap: Transport/lifetime cases are useful; share the aggregate corpus
--   with generic/fourway coverage instead of keeping another preparation framework.
-- Build status: QUARANTINED. Generation fixes lambda/partial-application/capture
--   shapes and exact worker arity; semantic tests must survive compiler optimization.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 150.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : SumInputFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for sum input.
module SumInputFixtures (prepareSumInputs) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (sort)
import qualified Data.Text as Text
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import THC.Compact.Module (readModuleValue)

walk :: Value -> [Value]
walk value = value : case value of
  Object fields -> concatMap walk (KeyMap.elems fields)
  Array fields -> concatMap walk (toList fields)
  _ -> []

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

prepareSumInputs :: FilePath -> IO ()
prepareSumInputs root = do
  let directory = "build/sum-input"
      output = root </> directory
      source = "t/fixtures/compiler/SumInputAudit.hs"
      driver = "t/fixtures/compiler/SumInputAuditNative.hs"
      entries = ["direct", "pap", "capture", "tailInput", "overapply", "emptyPayload", "referenceSlots",
                 "escapedPap", "escapedCapture"] :: [String]
      logs = directory </> "commands"
      native = directory </> "native"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Sum inputs require GHC 9.14.1")
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-it/fixtures/compiler", "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observed <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  unless (length (BSC.lines (commandStdout observed)) == 333) (die "Unexpected sum input oracle row count")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  artifacts <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        report = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ [source])
    let path = core </> "SumInputAudit.cbd"
    nodes <- BS.readFile (root </> path) >>= either die (pure . walk) . readModuleValue
    let inputs = [() | Array parts <- nodes, String "lam" : Array binders : _ <- [toList parts],
          Object binder <- toList binders, Just (Object proof) <- [KeyMap.lookup "rep" binder],
          KeyMap.lookup "aggregate" proof == Just (String "unboxed-sum")]
        prefixes = [() | Array parts <- nodes,
          String "app" : Array function : Array arguments : _ <- [toList parts],
          String "var" : String name : _ <- [toList function], ".consume" `Text.isSuffixOf` name,
          length (toList arguments) < 3]
        overArities = [length (toList parameters) | Object binding <- nodes,
          KeyMap.lookup "id" binding == Just (String "main:SumInputAudit.over"),
          Just (Array rhs) <- [KeyMap.lookup "expr" binding],
          String "lam" : Array parameters : _ <- [toList rhs]]
        captures = [() | Array parts <- nodes, String "lam" : Array parameters : body : _ <- [toList parts],
          let ids = [identifier | Object parameter <- toList parameters,
                Just identifier <- [KeyMap.lookup "id" parameter]],
          Array occurrence <- walk body,
          String "var" : identifier : Object metadata : _ <- [toList occurrence], identifier `notElem` ids,
          Just (Object proof) <- [KeyMap.lookup "rep" metadata],
          KeyMap.lookup "aggregate" proof == Just (String "unboxed-sum")]
    unless (length inputs >= 4 && not (null prefixes) && overArities == [2] && not (null captures))
      (die "Original Core lost sum formals, partial applications, captured sums or real overapplication")
    audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", report] ++
       concatMap (\entry -> ["--entry", "main:SumInputAudit." ++ entry]) entries ++ [path])
    result <- readJson (root </> report)
    case result of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
      _ -> die ("Strict sum input audit rejected " ++ stage)
    pure (report : path : commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputs <- hashes root $ sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/SumInputFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  outputs <- hashes root $ [directory </> "oracle.tsv", native </> "oracle"] ++ artifacts ++
    concatMap commandArtifacts [version, compiled, observed]
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "nativeRows" .= (333 :: Int), "inputHashes" .= inputs, "artifactHashes" .= outputs]
  putStrLn "sum-input: 333 native rows, 9 guest roots, pre/post strict audits accepted"
