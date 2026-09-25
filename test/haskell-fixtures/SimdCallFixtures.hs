-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module SimdCallFixtures (prepareSimdCalls) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value (..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import FixtureSupport (hashes, run, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (arch)

entries :: [String]
entries = ["directCase", "papCase", "nestedTupleCase", "joinCase", "overCase"]

inputs :: [Int]
inputs = [-65536, -32769, -32768, -1, 0, 1, 32767, 32768, 65535]

prepareSimdCalls :: FilePath -> IO ()
prepareSimdCalls root = do
  let directory = "build/simd-calls"
      output = root </> directory
      source = "compiler/test-fixtures/SimdCallAudit.hs"
      driver = "compiler/test-fixtures/SimdCallNative.hs"
      manifest = output </> "manifest.json"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "SIMD call fixture requires GHC 9.14.1")
  -- GHC's AArch64 NCG cannot emit SIMD instructions without LLVM. Match the
  -- existing SIMD fixture split: source/Core proof here, native oracle on x86.
  let exportOnly = arch == "aarch64"
      stages = if exportOnly then ["pre"] else ["pre", "post"]
  forM_ stages $ \stage -> do
    _ <- run root [("THC_CORE_OUT", output </> stage ++ "-core"),
                   ("THC_GHC_OUT", output </> stage ++ "-ghc")]
      "compiler/export.sh" (["-fno-code", "-fwrite-if-simplified-core" | exportOnly] ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source]) ""
    let core = directory </> stage ++ "-core/SimdCallAudit.json"
        audit = directory </> stage ++ "-audit.json"
    _ <- run root [] "python3" (["scripts/audit-core.py", core, "--output", audit] ++
      concatMap (\entry -> ["--entry", entry]) entries) ""
    report <- BS.readFile (root </> audit)
    case decodeStrict' report of
      Just (Object fields) | Just (Bool True) <- KeyMap.lookup "accepted" fields,
          Just (Array missing) <- KeyMap.lookup "missingGlobals" fields,
          Just (Array issues) <- KeyMap.lookup "issues" fields,
          null missing && null issues -> pure ()
      _ -> die ("SIMD call strict audit rejected " ++ stage)
  let requests = [(entry,x) | entry <- entries, x <- inputs]
      stdinRows = unlines [entry ++ " " ++ show x | (entry,x) <- requests]
      native = directory </> "native"
      binary = root </> native </> "simd-call-oracle"
  rows <- if exportOnly then pure [] else do
    createDirectoryIfMissing True (root </> native)
    _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
      "-icompiler/test-fixtures", "-odir", root </> native, "-hidir", root </> native,
      driver, "-o", binary] ""
    lines <$> run root [] binary [] stdinRows
  let parsed = traverse (\row -> case splitTab row of
        [entry,x,result] -> case (reads x, reads result) of
          ([(a,"")],[(b,"")]) -> Just (entry,a :: Int,b :: Int)
          _ -> Nothing
        _ -> Nothing) rows
  unless exportOnly $ case parsed of
    Just actual | length actual == length requests &&
      map (\(entry,x,_) -> (entry,x)) actual == requests -> pure ()
    _ -> die "Incomplete SIMD call native oracle"
  unless exportOnly $ writeFile (output </> "oracle.tsv") (unlines rows)
  let sources = [source,driver,"test/haskell-fixtures/SimdCallFixtures.hs",
        "test/haskell-fixtures/Main.hs","test/haskell-fixtures/FixtureSupport.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json","compiler/export.sh","compiler/build.sh"]
      artifacts = [directory </> "oracle.tsv" | not exportOnly] ++ [directory </> stage ++ suffix |
        stage <- stages, suffix <- ["-core/SimdCallAudit.json","-audit.json"]]
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "inputs" .= inputs, "stages" .= stages,
    "nativeRows" .= (if exportOnly then Nothing else Just (length rows)),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn ("simd-calls: " ++ show (length rows) ++ " native rows, strict " ++ show stages ++ " Core")
