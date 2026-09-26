-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module AtomicIntArrayFixtures (prepareAtomicIntArrays) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BSC
import Data.List (sort)
import qualified Data.Map.Strict as Map
import qualified Data.Set as Set
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

source, directory :: FilePath
source = "compiler/test-fixtures/AtomicIntArrayAudit.hs"
directory = "build/atomic-int-arrays"

entries :: [String]
entries = ["fetch" ++ op ++ "Result" | op <- ["Add", "Sub", "And", "Nand", "Or", "Xor"]] ++
  ["casInt" ++ width ++ "Result" | width <- ["", "8", "16", "32", "64"]] ++ ["atomicLoadStore"]

cases :: [(Integer, Integer, Integer)]
cases = Set.toAscList $ Set.fromList
  [(initial, wrap expected, replacement) |
    initial <- [-9223372036854775808, -2147483649, -32769, -129, -1, 0,
                127, 128, 32768, 2147483648, 9223372036854775807],
    expected <- [initial, initial + 1, initial + 256, 0, -1],
    replacement <- [-9223372036854775808, -129, 0, 128, 9223372036854775807]]
  where wrap x = (x + 9223372036854775808) `mod` 18446744073709551616 - 9223372036854775808

nativeDriver :: String
nativeDriver = unlines $
  ["{-# LANGUAGE MagicHash #-}", "module Main where", "import GHC.Exts (Int(I#))",
   "import qualified AtomicIntArrayAudit as P", "invoke :: String -> Int -> Int -> Int -> Int",
   "invoke name (I# a) (I# b) (I# c) = case name of"] ++
  ["  " ++ show name ++ " -> I# (P." ++ name ++ " a b c)" | name <- entries] ++
  ["  _ -> error \"unknown atomic-array entry\"", "emit :: [String] -> IO ()",
   "emit [name, a, b, c] = putStrLn (unwords [name, a, b, c, show (invoke name (read a) (read b) (read c))])",
   "emit _ = error \"invalid atomic-array row\"", "main :: IO ()",
   "main = getContents >>= mapM_ (emit . words) . lines"]

prepareAtomicIntArrays :: FilePath -> IO ()
prepareAtomicIntArrays root = do
  let output = root </> directory
      manifest = output </> "manifest.json"
      logs = directory </> "commands"
  createDirectoryIfMissing True output
  old <- doesFileExist manifest
  when old (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Atomic-array fixtures require GHC 9.14.1")
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "AtomicIntArrayAudit.json", core </> "THC.InterfaceClosure.json"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries]
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (options ++ [source])
    inventory <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (inventory == ["AtomicIntArrayAudit.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected atomic-array Core inventory: " ++ show inventory)
    audits <- forM entries $ \name -> do
      let report = stageDir </> name ++ ".audit.json"
      audit <- runLogged 60 root logs (stage ++ "-" ++ name ++ "-audit") [] "python3"
        (["scripts/audit-core.py", "--entry", name, "--output", report] ++ modules)
      pure (report : commandArtifacts audit)
    pure (stage, modules, commandArtifacts exported ++ concat audits)
  let native = directory </> "native"
      driver = directory </> "NativeAtomicIntArrays.hs"
      requests = directory </> "requests.tsv"
      oracle = directory </> "oracle.tsv"
      executable = root </> native </> "atomic-int-array-oracle"
  createDirectoryIfMissing True (root </> native)
  writeFile (root </> driver) nativeDriver
  writeFile (root </> requests) $ unlines
    [unwords [name, show a, show b, show c] | name <- entries, (a,b,c) <- cases]
  built <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
     "-hidir", root </> native, root </> driver, "-o", executable]
  observed <- runLoggedWithInput requests 30 root logs "native-oracle" [] executable []
  let observations = BSC.unpack (commandStdout observed)
      rows = map words (lines observations)
      input fields = case fields of
        [name,a,b,c,result] | Just x <- readInteger a, Just y <- readInteger b,
          Just z <- readInteger c, Just _ <- readInteger result -> Just (name,x,y,z)
        _ -> Nothing
      expected = [(name,a,b,c) | name <- entries, (a,b,c) <- cases]
  unless (map input rows == map Just expected) $
    die "Native atomic-array oracle has missing, malformed, or reordered rows"
  writeFile (root </> oracle) observations
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  inputHashes <- hashes root $ sort $
    [source, "test/haskell-fixtures/AtomicIntArrayFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
     "test/haskell-fixtures/Main.hs", "thc.cabal", "scripts/core-capabilities.json",
     "scripts/audit-core.py", "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh",
     "compiler/plugin.py", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
    ["scripts" </> file | file <- coreScripts, take 5 file == "core_", takeExtension file == ".py"]
  artifactHashes <- hashes root $ [driver, requests, oracle] ++
    commandArtifacts built ++ commandArtifacts observed ++ concat
      [modules ++ artifacts | (_, modules, artifacts) <- stages]
  writeJson manifest $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
     "nativeRows" .= length rows, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("atomic-int-arrays: " ++ show (length rows) ++ " native observations, pre/post strict audits")
