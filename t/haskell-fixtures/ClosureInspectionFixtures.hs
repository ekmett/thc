-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ClosureInspectionFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for closure inspection.
module ClosureInspectionFixtures (prepareClosureInspection) where
import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (isPrefixOf, sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareClosureInspection :: FilePath -> IO ()
prepareClosureInspection root = do
  let directory = "build/closure-inspection"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/ClosureInspectionAudit.hs"
      driver = "t/fixtures/compiler/ClosureInspectionNative.hs"
      entries = ["payload", "sizeConsistent", "pointerCount", "notStack", "noCCS", "noProvenance", "cleared", "annotated", "annotatedResume"] :: [String]
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Closure inspection fixtures require GHC 9.14.1")
  _ <- run root [("THC_CORE_OUT", output </> "core"), ("THC_GHC_OUT", output </> "ghc")]
    "bin/export-core.sh" [source] ""
  forM_ entries $ \entry -> do
    _ <- run root [] "python3" ["bin/audit-core.py", "--entry", entry,
      "--output", directory </> entry ++ ".audit.json", directory </> "core/ClosureInspectionAudit.json"] ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-fno-info-table-map", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "t/fixtures/compiler"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle") [] ""
  unless (length (lines observations) == 45) (die "Closure inspection native row count")
  writeFile (output </> "oracle.tsv") observations
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root (sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
    "t/haskell-fixtures/ClosureInspectionFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py",
    "bin/audit-core.py", "bin/core-capabilities.json"] ++
    ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root ([directory </> "oracle.tsv", directory </> "native/oracle",
    directory </> "core/ClosureInspectionAudit.json"] ++ [directory </> entry ++ ".audit.json" | entry <- entries])
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "nativeRows" .= (45 :: Int),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "closure-inspection: original Core for seven primops and 45 native observations"
