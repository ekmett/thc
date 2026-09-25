-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module WeakFixtures (prepareWeaks) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (hashes, readInteger, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

source, directory :: FilePath
source = "compiler/test-fixtures/WeakAudit.hs"
directory = "build/weak-explicit"

values :: [Integer]
values = [negate (2 ^ (63 :: Int)), -4097, -1, 0, 1, 42, 4097, 2 ^ (63 :: Int) - 1]

nativeDriver :: String
nativeDriver = unlines
  ["{-# LANGUAGE MagicHash #-}", "module Main where", "import GHC.Exts (Int(I#))",
   "import qualified WeakAudit as P", "emit :: Int -> IO ()",
   "emit input@(I# raw) = putStrLn (show input ++ \"\\t\" ++ show (I# (P.weakComposite raw)))",
   "main :: IO ()", "main = getContents >>= mapM_ (emit . read) . lines"]

prepareWeaks :: FilePath -> IO ()
prepareWeaks root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
      native = directory </> "native"
      driver = directory </> "NativeWeak.hs"
      oracle = directory </> "oracle.tsv"
      executable = root </> native </> "weak-oracle"
  createDirectoryIfMissing True (root </> native)
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Explicit weak fixture requires GHC 9.14.1")
  writeFile (root </> driver) nativeDriver
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", executable] ""
  observations <- runWithTimeout (Just 30000000) root [] executable [] (unlines (map show values))
  let rows = map words (lines observations)
      parsed = traverse (\fields -> case fields of
        [input, result] -> (,) <$> readInteger input <*> readInteger result
        _ -> Nothing) rows
      signed n = (n + 2 ^ (63 :: Int)) `mod` 2 ^ (64 :: Int) - 2 ^ (63 :: Int)
  unless (parsed == Just [(input, signed (input + 58)) | input <- values]) $
    die ("Native explicit weak contract mismatch: " ++ observations)
  writeFile (root </> oracle) observations
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "WeakAudit.json", core </> "THC.InterfaceClosure.json"]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        ["-fplugin-opt=THC.Plugin:closure=weakComposite", source]) ""
    exported <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (exported == ["THC.InterfaceClosure.json", "WeakAudit.json"]) $
      die ("Unexpected explicit weak module inventory: " ++ show exported)
    _ <- run root [] "python3" (["scripts/audit-core.py", "--entry", "weakComposite", "--output",
      stageDir </> "audit.json"] ++ modules) ""
    pure (stage, modules)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, "test/haskell-fixtures/WeakFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs", "thc.cabal", "scripts/core-capabilities.json", "scripts/audit-core.py",
        "src/main/resources/thc/scalar-primop-signatures.json", "compiler/plugin.py",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh"] ++
        ["compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
        ["scripts" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]
      artifacts = [driver, oracle] ++ concat [modules ++ [directory </> stage </> "audit.json"] | (stage, modules) <- stages]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entry" .= ("weakComposite" :: String), "stages" .= Map.fromList stages,
    "nativeRows" .= length rows, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("weak-explicit: " ++ show (length rows) ++ " native observations, pre/post strict audits; PARTIAL/no GC")
