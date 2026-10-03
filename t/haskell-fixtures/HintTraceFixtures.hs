-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (060 hint-trace)
-- Purpose: Check hint/trace/event/marker primops preserve program results and observable
--   trace behavior.
-- Produces/consumed result: Two CBDs, two batched audits and native oracle.tsv.
-- Cost and overlap: Check THC trace records, bounds/lifetimes and non-strict prefetch
--   behavior. Native GHC supplies result values; its eventlog encoding is not a THC
--   contract and is not generated. JFR wiring needs separate tests.
-- Build status: CMake owns the named products; no generated directory is an input.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 060.
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : HintTraceFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for hint trace.
module HintTraceFixtures (prepareHintTrace) where
import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (isPrefixOf, sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareHintTrace :: FilePath -> IO ()
prepareHintTrace root = do
  let directory = "build/hint-trace"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/HintTraceAudit.hs"
      driver = "t/fixtures/compiler/HintTraceNative.hs"
      entries = ["hints", "traces", "event", "marker", "binary", "addressHints"] :: [String]
      stages = ["pre", "post"] :: [String]
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Hint/trace fixtures require GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source]) ""
    _ <- run root [] "python3"
      (["bin/audit-core.py", "--output", directory </> stage </> "audit.json", core </> "HintTraceAudit.cbd"] ++
       concatMap (\entry -> ["--entry", "main:HintTraceAudit." ++ entry]) entries) ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "t/fixtures/compiler"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle")
    [] ""
  let expected = unlines [name ++ "\t" ++ show x ++ "\t" ++ show (x + delta)
        | x <- [-3,0,1,37,999 :: Int], (name,delta) <- [("hints",1),("traces",19)]]
  unless (observations == expected) (die "Native hint/trace results disagree")
  writeFile (output </> "oracle.tsv") observations
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root (sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/HintTraceFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
    "bin/audit-core.py", "bin/core-capabilities.json"] ++
    ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root ([directory </> "oracle.tsv", directory </> "native/oracle"] ++
    [directory </> stage </> suffix | stage <- stages,
      suffix <- ["core/HintTraceAudit.cbd", "audit.json"]])
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "nativeRows" .= (10 :: Int),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "hint-trace: 19 primops, strict pre/post Core, 10 native observations and target trace controls"
