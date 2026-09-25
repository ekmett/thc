-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module FetchAddIntArrayFixtures (prepareFetchAddIntArray) where

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
source = "compiler/test-fixtures/FetchAddIntArrayAudit.hs"
directory = "build/fetch-add-int-array"

entries :: [String]
entries = ["fetchComposite"]

cases :: [(Integer, Integer, Integer)]
cases = [(initial, first, second) |
  initial <- [-9223372036854775808, -7, 0, 1, 9223372036854775807],
  first <- [-2, 0, 1, 9223372036854775807], second <- [-1, 0, 3]]

nativeDriver :: String
nativeDriver = unlines
  ["{-# LANGUAGE MagicHash #-}", "module Main where",
   "import GHC.Exts (Int(I#), Int#)", "import qualified FetchAddIntArrayAudit as P",
   "emit :: Int -> Int -> Int -> IO ()",
   "emit initial@(I# a) first@(I# b) second@(I# c) = putStrLn",
   "  (show initial ++ \"\\t\" ++ show first ++ \"\\t\" ++ show second ++",
   "    \"\\t\" ++ show (I# (P.fetchComposite a b c)))",
   "dispatch :: [String] -> IO ()",
   "dispatch [initial, first, second] = emit (read initial) (read first) (read second)",
   "dispatch _ = error \"invalid fetch-add row\"",
   "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

prepareFetchAddIntArray :: FilePath -> IO ()
prepareFetchAddIntArray root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Fetch-add fixture requires GHC 9.14.1")
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "FetchAddIntArrayAudit.json", core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (postTidy ++ roots ++ [source]) ""
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine fetch-add Core export: " ++ path))) modules
    exported <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (exported == ["FetchAddIntArrayAudit.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected fetch-add Core module inventory: " ++ show exported)
    _ <- forM entries $ \name -> do
      let report = stageDir </> name ++ ".audit.json"
      _ <- run root [] "python3" (["scripts/audit-core.py", "--entry", name, "--output", report] ++ modules) ""
      pure ()
    pure (stage, modules)
  let native = directory </> "native"
      driver = directory </> "NativeFetchAddIntArray.hs"
      oracle = directory </> "oracle.tsv"
      executable = root </> native </> "fetch-add-oracle"
  createDirectoryIfMissing True (root </> native)
  writeFile (root </> driver) nativeDriver
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", executable] ""
  observations <- runWithTimeout (Just 30000000) root [] executable []
    (concat [show a ++ "\t" ++ show b ++ "\t" ++ show c ++ "\n" | (a,b,c) <- cases])
  let rows = [words line | line <- lines observations]
      inputRow fields = case fields of
        [a,b,c,result] | Just x <- readInteger a, Just y <- readInteger b,
          Just z <- readInteger c, Just _ <- readInteger result -> Just (x,y,z)
        _ -> Nothing
      actual = map inputRow rows
      expected = Set.fromList cases
  unless (length rows == Set.size expected && Set.fromList actual == Set.map Just expected) $
    die "Native fetch-add oracle has missing or duplicate rows"
  writeFile (root </> oracle) observations
  let inputs = sort [source, "test/haskell-fixtures/FetchAddIntArrayFixtures.hs",
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
  putStrLn ("fetch-add-int-array: " ++ show (length rows) ++ " native observations, pre/post strict audits")
