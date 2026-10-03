-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (055 ghc-bco)
-- Purpose: Check the runtime contract for GHC bytecode-object primops and unsupported
--   cases.
-- Produces/consumed result: Two CBDs consumed by GhcBCOTest.
-- Cost and overlap: Native results exercise BCO execution, sharing and rejection
--   boundaries. One multi-entry audit per stage covers the exported entry set.
-- Build status: cmake/AuditedFixtures.cmake owns the named files; no directory scan.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 055.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : GhcBCOFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for ghc bco.
module GhcBCOFixtures (prepareGhcBCO) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareGhcBCO :: FilePath -> IO ()
prepareGhcBCO root = do
  let directory = "build/ghc-bco"
      output = root </> directory
      source = "t/fixtures/core/GhcBCO.hs"
      driver = "t/fixtures/compiler/GhcBCONative.hs"
      entries = ["bcoConstant", "bcoApply", "bcoApplyTwo", "bcoFunction", "bcoArithmetic", "bcoBranch", "bcoLargeOperand", "bcoSharing", "bcoCase", "bcoCaseNested", "bcoCasePointer", "bcoCaseFloat", "bcoCaseDouble", "bcoCaseLong", "bcoCaseVoid", "bcoPacked8", "bcoPacked16", "bcoPacked32", "bcoCaseTuple", "bcoCaseTupleCall", "bcoCaseTupleOverapply", "bcoCapturedPap", "bcoCapturedAp", "bcoCapturedNoUpd", "bcoCapturedApChain", "bcoCapturedRecursive", "bcoCapturedFloat", "bcoCapturedDouble", "bcoCapturedLong", "bcoCapturedNoUpdEscape", "bcoApplyIntCore", "bcoApplyFloatCore", "bcoApplyDoubleCore", "bcoApplyLongCore", "bcoApplyVoidCore"]
      stages = ["pre", "post"]
      logs = directory </> "commands"
      native = directory </> "native"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "BCO format requires GHC 9.14.1")
  compiled <- runLogged 120 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-it/fixtures/core",
     "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observations <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  let values = map (read . BSC.unpack) (BSC.lines (commandStdout observations)) :: [Integer]
  unless (length values == 3 * length entries) (die "Unexpected BCO native row count")
  artifacts <- fmap concat $ forM stages $ \stage -> do
    let core = directory </> stage </> "core"
    exported <- runLogged 180 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    let report = directory </> stage </> "audit.json"
    audited <- runLogged 30 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", report, core </> "GhcBCO.cbd"] ++
       concatMap (\entry -> ["--entry", "main:GhcBCO." ++ entry]) entries)
    bytes <- BS.readFile (root </> report)
    case decodeStrict' bytes of
      Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
        KeyMap.lookup "issues" value == Just (Array mempty),
        KeyMap.lookup "missingGlobals" value == Just (Array mempty) -> pure ()
      _ -> die ("Strict BCO audit rejected " ++ stage)
    pure ([core </> "GhcBCO.cbd", report] ++ commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let sources = [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/GhcBCOFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root (artifacts ++ concatMap commandArtifacts [version, compiled, observations])
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "stages" .= stages, "arguments" .= ([-2,0,7] :: [Int]), "native" .= values,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("ghc-bco: " ++ show (length values) ++ " native observations and two multi-entry pre/post Core audits")
