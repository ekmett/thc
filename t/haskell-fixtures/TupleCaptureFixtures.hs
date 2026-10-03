-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (151 tuple-capture)
-- Purpose: Check escaped tuple captures, thunk sharing, independent closures,
--   PAP reuse, empty/state captures and unforced lazy fields.
-- Consumes: TupleCaptureAudit{,Native}.hs, selected GHC, exporter and auditor.
-- Produces/consumed result: Named pre/post CBDs, 296 native rows, audits and manifest.
-- Cost and overlap: Retain capture lifetime/sharing in shared aggregate coverage;
--   direct representation controls should not require a separate native pipeline.
-- Build status: QUARANTINED. Producer requires at least eight capture occurrences;
--   consumer also fixes capture storage/image sizes that optimization can change.
-- Detailed inputs/outputs: docs/fixture-inputs.log, entry 151.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : TupleCaptureFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for tuple capture.
module TupleCaptureFixtures (prepareTupleCaptures) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Foldable (toList)
import Data.List (sort)
import FixtureSupport
import THC.Compact.Module (readModuleValue)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

walk :: Value -> [Value]
walk value = value : case value of
  Object fields -> concatMap walk (KeyMap.elems fields)
  Array fields -> concatMap walk (toList fields)
  _ -> []

readJson :: FilePath -> IO Value
readJson path = BS.readFile path >>= either die pure . eitherDecodeStrict'

readCore :: FilePath -> IO Value
readCore path = BS.readFile path >>= either die pure . readModuleValue

prepareTupleCaptures :: FilePath -> IO ()
prepareTupleCaptures root = do
  let directory = "build/tuple-capture"
      output = root </> directory
      source = "t/fixtures/compiler/TupleCaptureAudit.hs"
      driver = "t/fixtures/compiler/TupleCaptureAuditNative.hs"
      entries = ["escaped", "thunk", "independent", "papReuse", "nested", "emptyCapture",
                 "stateCapture", "lazyCapture"] :: [String]
      logs = directory </> "commands"
      native = directory </> "native"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True (root </> native)
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Tuple captures require GHC 9.14.1")
  compiled <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-it/fixtures/compiler", "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observed <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  unless (length (BSC.lines (commandStdout observed)) == 296) (die "Unexpected tuple capture oracle row count")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  artifacts <- fmap concat $ forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        report = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries] ++ [source])
    let path = core </> "TupleCaptureAudit.cbd"
    nodes <- walk <$> readCore (root </> path)
    let captures = [() | Array parts <- nodes, String "lam" : Array parameters : body : _ <- [toList parts],
          let ids = [identifier | Object parameter <- toList parameters,
                Just identifier <- [KeyMap.lookup "id" parameter]],
          Array occurrence <- walk body,
          String "var" : identifier : Object metadata : _ <- [toList occurrence], identifier `notElem` ids,
          Just (Object proof) <- [KeyMap.lookup "rep" metadata],
          KeyMap.lookup "aggregate" proof == Just (String "unboxed-tuple")]
    unless (length captures >= 8) (die "Original Core lost its whole-tuple closure captures")
    audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", report] ++
       concatMap (\entry -> ["--entry", "main:TupleCaptureAudit." ++ entry]) entries ++ [path])
    result <- readJson (root </> report)
    case result of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
      _ -> die ("Strict tuple capture audit rejected " ++ stage)
    pure (report : path : commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputs <- hashes root $ sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/TupleCaptureFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
    "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  outputs <- hashes root $ [directory </> "oracle.tsv", native </> "oracle"] ++ artifacts ++
    concatMap commandArtifacts [version, compiled, observed]
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "nativeRows" .= (296 :: Int), "inputHashes" .= inputs, "artifactHashes" .= outputs]
  putStrLn "tuple-capture: 296 native rows, 8 guest roots, pre/post strict audits accepted"
