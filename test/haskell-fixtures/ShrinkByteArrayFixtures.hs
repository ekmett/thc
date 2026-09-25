-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module ShrinkByteArrayFixtures (prepareShrinkByteArrays) where

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
source = "compiler/test-fixtures/ShrinkMutableByteArrayAudit.hs"
directory = "build/shrink-bytearrays"

entries :: [String]
entries = ["shrinkBytes", "shrinkPinned"]

cases :: [(String, Integer, Integer)]
cases = [(name, seed, size) | name <- entries, seed <- [-7, 0, 1, 42, 255],
  size <- if name == "shrinkPinned" then [1, 2, 7, 16] else [0, 1, 2, 7, 16]]

nativeDriver :: String
nativeDriver = unlines
  ["{-# LANGUAGE MagicHash #-}", "module Main where",
   "import GHC.Exts (Int(I#), Int#)", "import qualified ShrinkMutableByteArrayAudit as P",
   "emit :: String -> (Int# -> Int# -> Int#) -> Int -> Int -> IO ()",
   "emit name function seed@(I# raw) size@(I# count) = putStrLn",
   "  (name ++ \"\\t\" ++ show seed ++ \"\\t\" ++ show size ++ \"\\t\" ++ show (I# (function raw count)))",
   "dispatch :: [String] -> IO ()", "dispatch [name, seed, size] = case name of",
   "  \"shrinkBytes\" -> emit name P.shrinkBytes (read seed) (read size)",
   "  \"shrinkPinned\" -> emit name P.shrinkPinned (read seed) (read size)",
   "  _ -> error \"unknown shrink entry\"",
   "dispatch _ = error \"invalid shrink row\"",
   "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

prepareShrinkByteArrays :: FilePath -> IO ()
prepareShrinkByteArrays root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Shrink fixture requires GHC 9.14.1")
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "ShrinkMutableByteArrayAudit.json", core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (postTidy ++ roots ++ [source]) ""
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine shrink Core export: " ++ path))) modules
    exported <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (exported == ["ShrinkMutableByteArrayAudit.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected shrink Core module inventory: " ++ show exported)
    _ <- forM entries $ \name -> do
      let report = stageDir </> name ++ ".audit.json"
      _ <- run root [] "python3" (["scripts/audit-core.py", "--entry", name, "--output", report] ++ modules) ""
      pure ()
    pure (stage, modules)
  let native = directory </> "native"
      driver = directory </> "NativeShrinkByteArrays.hs"
      oracle = directory </> "oracle.tsv"
      executable = root </> native </> "shrink-bytearray-oracle"
  createDirectoryIfMissing True (root </> native)
  writeFile (root </> driver) nativeDriver
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", executable] ""
  observations <- runWithTimeout (Just 30000000) root [] executable []
    (concat [name ++ "\t" ++ show seed ++ "\t" ++ show size ++ "\n" | (name, seed, size) <- cases])
  let rows = [words line | line <- lines observations]
      inputRow fields = case fields of
        [name, seed, size, result] | Just x <- readInteger seed,
          Just n <- readInteger size, Just _ <- readInteger result -> Just (name, x, n)
        _ -> Nothing
      actual = map inputRow rows
      expected = Set.fromList cases
  unless (length rows == Set.size expected && Set.fromList actual == Set.map Just expected) $
    die "Native shrink oracle has missing or duplicate rows"
  writeFile (root </> oracle) observations
  let inputs = sort [source, "test/haskell-fixtures/ShrinkByteArrayFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
        "scripts/core-capabilities.json", "scripts/audit-core.py",
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
  putStrLn ("shrink-bytearrays: " ++ show (length rows) ++ " native observations, pre/post strict audits")
