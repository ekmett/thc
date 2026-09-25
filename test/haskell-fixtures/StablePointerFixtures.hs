-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module StablePointerFixtures (prepareStablePointers) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import FixtureSupport (hashes, readInteger, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

source, directory :: FilePath
source = "compiler/test-fixtures/StablePointerAudit.hs"
directory = "build/stable-pointers"

entries :: [String]
entries = ["stableComposite", "lazyStable", "sharedEventManagerStore", "sharedSignalHandlerStore"]

values :: [Integer]
values = [negate (2 ^ (63 :: Int)), -4097, -1, 0, 1, 42, 4097, 2 ^ (63 :: Int) - 1]

nativeDriver :: String
nativeDriver = unlines $
  ["{-# LANGUAGE MagicHash #-}", "module Main where",
   "import GHC.Exts (Int(I#), Int#)", "import qualified StablePointerAudit as P",
   "emit :: String -> (Int# -> Int#) -> Int -> IO ()",
   "emit name function input@(I# raw) = putStrLn (name ++ \"\\t\" ++ show input ++ \"\\t\" ++ show (I# (function raw)))",
   "dispatch :: [String] -> IO ()", "dispatch [name, input] = case name of",
   "  \"stableComposite\" -> emit name P.stableComposite (read input)",
   "  \"lazyStable\" -> emit name P.lazyStable (read input)",
   "  \"sharedEventManagerStore\" -> emit name P.sharedEventManagerStore (read input)",
   "  \"sharedSignalHandlerStore\" -> emit name P.sharedSignalHandlerStore (read input)",
   "  _ -> error \"unknown StablePtr entry\"",
   "dispatch _ = error \"invalid StablePtr row\"",
   "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

prepareStablePointers :: FilePath -> IO ()
prepareStablePointers root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "StablePtr fixture requires GHC 9.14.1")
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "StablePointerAudit.json", core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (postTidy ++ roots ++ [source]) ""
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine StablePtr Core export: " ++ path))) modules
    exported <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (exported == ["StablePointerAudit.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected StablePtr Core module inventory: " ++ show exported)
    _ <- forM entries $ \name -> do
      let report = stageDir </> name ++ ".audit.json"
      _ <- run root [] "python3" (["scripts/audit-core.py", "--entry", name, "--output", report] ++ modules) ""
      pure ()
    pure (stage, modules)
  let native = directory </> "native"
      driver = directory </> "NativeStablePointer.hs"
      oracle = directory </> "oracle.tsv"
      executable = root </> native </> "stable-pointer-oracle"
  createDirectoryIfMissing True (root </> native)
  writeFile (root </> driver) nativeDriver
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", executable] ""
  observations <- runWithTimeout (Just 30000000) root [] executable []
    (concat [name ++ "\t" ++ show x ++ "\n" | name <- entries, x <- values])
  let rows = [words line | line <- lines observations]
      inputRow fields = case fields of
        [name, input, result] | Just n <- readInteger input, Just _ <- readInteger result -> Just (name, n)
        _ -> Nothing
      actual = map inputRow rows
      expected = Set.fromList [(name, x) | name <- entries, x <- values]
  unless (length rows == Set.size expected && Set.fromList actual == Set.map Just expected) $
    die "Native StablePtr oracle has missing or duplicate rows"
  writeFile (root </> oracle) observations
  let inputs = sort [source, "test/haskell-fixtures/StablePointerFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
        "scripts/core-capabilities.json", "scripts/core_original_foreign.py", "scripts/audit-core.py",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh"]
      artifacts = [driver, oracle] ++ concat
        [modules ++ [directory </> stage </> name ++ ".audit.json" | name <- entries]
          | (stage, modules) <- stages]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "stages" .= Map.fromList stages, "nativeRows" .= length rows,
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("stable-pointers: " ++ show (length rows) ++ " native observations, pre/post strict audits")
