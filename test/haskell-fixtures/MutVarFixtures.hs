-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- Produce genuine GHC Core and native observations. MutVarTest owns the
-- independent arithmetic model and runtime/representation checks.
module MutVarFixtures (prepareMutVar) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import FixtureSupport (hashes, readInteger, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

entries :: [String]
entries = ["stRef", "lazyRef", "closureRef", "orderedRef", "unliftedRef", "stLoop",
           "stRefEquality", "lazyRefEquality", "lazyIORef", "swapRef", "lazySwapRef",
           "modifyRef", "lazyModifyRef", "lazyBottomModifierRef"]

source, directory :: FilePath
source = "compiler/test-fixtures/MutVarAudit.hs"
directory = "build/mutvar"

values :: [Integer]
values = let low = negate (2 ^ (63 :: Int))
             high = 2 ^ (63 :: Int) - 1
             trillion = 10 ^ (12 :: Int)
         in Set.toAscList $ Set.fromList
           ([-128..128] ++ [low, low + 1, high - 1, high,
                             -4097, 4097, negate trillion, trillion])

nativeDriver :: String
nativeDriver = unlines $
  ["{-# LANGUAGE MagicHash #-}", "module Main where",
   "import GHC.Exts (Int(I#), Int#)", "import qualified MutVarAudit as P",
   "emit :: String -> (Int# -> Int#) -> Int -> IO ()",
   "emit n f x@(I# a) = putStrLn (n ++ \"\\t\" ++ show x ++ \"\\t\" ++ show (I# (f a)))",
   "dispatch :: [String] -> IO ()", "dispatch [name, x] = case name of"] ++
  ["  " ++ show name ++ " -> emit " ++ show name ++ " P." ++ name ++ " (read x)" | name <- entries] ++
  ["  _ -> error \"unknown entry\"", "dispatch _ = error \"invalid input\"",
   "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

parseRow :: String -> IO (String, Integer)
parseRow line = case words line of
  [name,input,result] | Just x <- readInteger input,
                        Just _ <- readInteger result -> pure (name,x)
  _ -> die ("Malformed native MutVar row: " ++ line)

prepareMutVar :: FilePath -> IO ()
prepareMutVar root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "MutVar fixture requires GHC 9.14.1")
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "MutVarAudit.json", core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries]
    _ <- run root [("THC_CORE_OUT", root </> core),
                   ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (postTidy ++ roots ++ [source]) ""
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine MutVar Core export: " ++ path))) modules
    exportedModules <- sort . filter ((== ".json") . takeExtension) <$>
      listDirectory (root </> core)
    unless (exportedModules == ["MutVarAudit.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected MutVar Core module inventory: " ++ show exportedModules)
    mapM_ (\name -> do
      let report = stageDir </> name ++ ".audit.json"
      _ <- run root [] "python3"
        (["scripts/audit-core.py", "--entry", name, "--output", report] ++ modules) ""
      pure ()) entries
    pure (stage,modules)
  let native = directory </> "native"
      driver = directory </> "NativeMutVar.hs"
      oracle = directory </> "oracle.tsv"
      executable = root </> native </> "mutvar-oracle"
  createDirectoryIfMissing True (root </> native)
  writeFile (root </> driver) nativeDriver
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", executable] ""
  observations <- runWithTimeout (Just 60000000) root [] executable []
    (concat [name ++ "\t" ++ show x ++ "\n" | name <- entries, x <- values])
  rows <- mapM parseRow (lines observations)
  let expected = Set.fromList [(name,x) | name <- entries, x <- values]
  unless (length rows == Set.size expected && Set.fromList rows == expected) $
    die "Native MutVar oracle has missing or duplicate entry/input rows"
  writeFile (root </> oracle) observations
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, "test/haskell-fixtures/MutVarFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
        "scripts/core-capabilities.json", "scripts/audit-core.py",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh"] ++
        ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"] ++
        ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"]
      artifacts = [driver,oracle] ++ concat
        [modules ++ [directory </> stage </> name ++ ".audit.json" | name <- entries]
          | (stage,modules) <- stages]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
     "entries" .= entries, "stages" .= Map.fromList stages,
     "nativeRows" .= length rows, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes]
  putStrLn ("mutvar: " ++ show (length entries) ++ " genuine workloads, " ++
            show (length rows) ++ " native observations, pre/post strict Core audits")
