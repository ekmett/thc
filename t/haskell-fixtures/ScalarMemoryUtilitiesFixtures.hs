-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (053 scalar-memory-utilities)
-- Purpose: Check memory utility operations preserve bytes, lengths and offsets.
-- Produces/consumed result: CBDs and native oracle.tsv.
-- Cost and overlap: Keep only THC-owned transport/aliasing semantics. Hundreds of
--   observations do not justify retesting arbitrary libc implementations; consolidate
--   overlapping copies/fills.
-- Build status: Admitted by fixture-scalar-memory-utilities in CMake. Native values
--   and managed lifetime/aliasing checks are independent of compiler root layouts.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 053.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ScalarMemoryUtilitiesFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for scalar memory utilities.
module ScalarMemoryUtilitiesFixtures (prepareScalarMemoryUtilities) where
import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareScalarMemoryUtilities :: FilePath -> IO ()
prepareScalarMemoryUtilities root = do
  let directory = "build/scalar-memory-utilities"
      output = root </> directory
      manifest = output </> "manifest.json"
      logs = directory </> "commands"
      fixture = "t/fixtures/compiler/ScalarMemoryUtilities.hs"
      driver = "t/fixtures/compiler/ScalarMemoryUtilitiesNative.hs"
      entries = ["memoryCase", "pinCase", "thawCase", "shrinkCase", "differenceCase", "remainderCase",
                 "numericDifference", "numericRemainder"]
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Scalar memory utilities require GHC 9.14.1")
  let native = output </> "native"
  createDirectoryIfMissing True native
  build <- runLogged 240 root logs "native-build" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-it/fixtures/compiler",
     "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  oracle <- runLogged 60 root logs "native-oracle" [] (native </> "oracle") []
  unless (length (BSC.lines (commandStdout oracle)) == 271) (die "Wrong scalar memory native corpus size")
  BS.writeFile (output </> "oracle.tsv") (commandStdout oracle)
  exported <- forM stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        auditPath = directory </> stage </> "audit.json"
    exported <- runLogged 240 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [fixture])
    audited <- runLogged 120 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", core </> "ScalarMemoryUtilities.cbd", "--output", auditPath] ++
       concatMap (\entry -> ["--entry","main:ScalarMemoryUtilities." ++ entry]) entries)
    bytes <- BS.readFile (root </> auditPath)
    case decodeStrict' bytes of
      Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
        KeyMap.lookup "issues" value == Just (Array mempty),
        KeyMap.lookup "missingGlobals" value == Just (Array mempty) -> pure ()
      _ -> die ("Strict scalar memory utilities audit rejected " ++ stage)
    pure ([core </> "ScalarMemoryUtilities.cbd", auditPath] ++
      commandArtifacts exported ++ commandArtifacts audited)
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let inputs = sort $ [fixture,driver,"thc.cabal","t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/ScalarMemoryUtilitiesFixtures.hs","t/haskell-fixtures/FixtureSupport.hs",
        "bin/audit-core.py","bin/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, "core_" `isPrefixOf` file, takeExtension file == ".py"]
      artifacts = [directory </> "oracle.tsv", directory </> "native/oracle"] ++
        commandArtifacts build ++ commandArtifacts oracle ++ concat exported
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "nativeRows" .= (271 :: Int),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "scalar-memory-utilities: 271 native rows, ten primops, strict pre/post Core"
