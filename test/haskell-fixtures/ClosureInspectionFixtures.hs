-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
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
      source = "compiler/test-fixtures/ClosureInspectionAudit.hs"
      driver = "compiler/test-fixtures/ClosureInspectionNative.hs"
      entries = ["payload", "sizeConsistent", "pointerCount", "notStack", "noCCS", "noProvenance", "cleared", "annotated", "annotatedResume"] :: [String]
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Closure inspection fixtures require GHC 9.14.1")
  _ <- run root [("THC_CORE_OUT", output </> "core"), ("THC_GHC_OUT", output </> "ghc")]
    "compiler/export.sh" [source] ""
  forM_ entries $ \entry -> do
    _ <- run root [] "python3" ["scripts/audit-core.py", "--entry", entry,
      "--output", directory </> entry ++ ".audit.json", directory </> "core/ClosureInspectionAudit.json"] ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-fno-info-table-map", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle") [] ""
  unless (length (lines observations) == 45) (die "Closure inspection native row count")
  writeFile (output </> "oracle.tsv") observations
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root (sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
    "test/haskell-fixtures/ClosureInspectionFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
    "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py",
    "scripts/audit-core.py", "scripts/core-capabilities.json"] ++
    ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"])
  artifactHashes <- hashes root ([directory </> "oracle.tsv", directory </> "native/oracle",
    directory </> "core/ClosureInspectionAudit.json"] ++ [directory </> entry ++ ".audit.json" | entry <- entries])
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "nativeRows" .= (45 :: Int),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "closure-inspection: original Core for seven primops and 45 native observations"
