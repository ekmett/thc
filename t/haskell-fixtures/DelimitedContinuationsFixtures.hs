-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (056 delimited-continuations)
-- Purpose: Check prompt/control capture, resumption and case-arm continuation behavior.
-- Produces/consumed result: Continuation and parked-control CBDs.
-- Cost and overlap: Keep runtime control-flow semantics, sharing and rejection boundaries.
--   Three batched audits cover the same entries; execution checks native values,
--   shared state and first compiled calls without predicting compiler root counts.
-- Build status: CMake owns each named product; native and Core object directories
--   are separate for each program and stage.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 056.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : DelimitedContinuationsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for delimited continuations.
module DelimitedContinuationsFixtures (prepareDelimitedContinuations) where

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

prepareDelimitedContinuations :: FilePath -> IO ()
prepareDelimitedContinuations root = do
  let directory = "build/delimited-continuations"
      output = root </> directory
      source = "t/fixtures/core/DelimitedContinuations.hs"
      driver = "t/fixtures/compiler/DelimitedContinuationsNative.hs"
      parkedSource = "t/fixtures/core/ParkedControl.hs"
      parkedDriver = "t/fixtures/compiler/ParkedControlNative.hs"
      parked = directory </> "parked"
      entries = ["promptPure", "abortSuffix", "resumeTwice", "nestedPrompts", "sameTagNearest", "capturedCatch", "capturedMask", "escapedResume", "ambientMask", "resumedTail", "resumedJoin", "resumedScalar", "recapturedMask", "resumedApplication", "resumedScalarApplication", "polymorphicApplications", "polymorphicScalarApplications"]
      stages = ["pre", "post"]
      logs = directory </> "commands"
      native = directory </> "native"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Delimited continuations require GHC 9.14.1")
  compiled <- runLogged 120 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-it/fixtures/core",
     "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observations <- runLogged 30 root logs "native-run" [] (output </> "native/oracle") []
  let values = map (read . BSC.unpack) (BSC.lines (commandStdout observations)) :: [Integer]
  unless (length values == 51) (die "Unexpected delimited-continuation native row count")
  createDirectoryIfMissing True (root </> parked </> "native")
  parkedCompiled <- runLogged 120 root logs "parked-native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-it/fixtures/core",
     "-odir", parked </> "native", "-hidir", parked </> "native", parkedDriver, "-o", parked </> "native/oracle"]
  parkedObserved <- runLogged 30 root logs "parked-native-run" [] (root </> parked </> "native/oracle") []
  let parkedValues = map (read . BSC.unpack) (BSC.lines (commandStdout parkedObserved)) :: [Integer]
  unless (parkedValues == [11023000000, 107119096192]) (die "Unexpected parked-continuation native observations")
  parkedExported <- runLogged 180 root logs "parked-export"
    [("THC_CORE_OUT", root </> parked </> "core"), ("THC_GHC_OUT", root </> parked </> "ghc")]
    "bin/export-core.sh" [parkedSource]
  parkedAudited <- runLogged 30 root logs "parked-audit" [] "python3"
    ["bin/audit-core.py", "--entry", "main:ParkedControl.observe", "--output", parked </> "audit.json", parked </> "core/ParkedControl.cbd"]
  artifacts <- fmap concat $ forM stages $ \stage -> do
    let core = directory </> stage </> "core"
    exported <- runLogged 180 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source])
    let report = directory </> stage </> "audit.json"
    audited <- runLogged 30 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", report, core </> "DelimitedContinuations.cbd"] ++
       concatMap (\entry -> ["--entry", "main:DelimitedContinuations." ++ entry]) entries)
    bytes <- BS.readFile (root </> report)
    case decodeStrict' bytes of
      Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
        KeyMap.lookup "issues" value == Just (Array mempty),
        KeyMap.lookup "missingGlobals" value == Just (Array mempty) -> pure ()
      _ -> die ("Strict continuation audit rejected " ++ stage)
    pure ([core </> "DelimitedContinuations.cbd", report] ++ commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let sources = [source, driver, parkedSource, parkedDriver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/DelimitedContinuationsFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root (artifacts ++ [parked </> "core/ParkedControl.cbd", parked </> "audit.json"] ++
    concatMap commandArtifacts [version, compiled, observations, parkedCompiled, parkedObserved, parkedExported, parkedAudited])
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "stages" .= stages, "arguments" .= ([-2,0,7] :: [Int]), "native" .= values, "parkedNative" .= parkedValues,
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "delimited-continuations: 51 native observations and original pre/post Core audits"
