-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (017 tuple-arithmetic)
-- Purpose: Check carry, wide multiply and quotient/remainder return all result components
--   correctly.
-- Produces/consumed result: CBDs, oracle.tsv and call-oracle.tsv.
-- Cost and overlap: Keep multi-result arithmetic and cross-call cases. One native
--   executable already serves both; this producer must solely own its CBDs.
-- Cost: A small boundary oracle covers signed overflow, carry/borrow, wide
--   multiplication and division signs. No random corpus or per-bit cross product.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 017.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : AggregateFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for aggregate.
module AggregateFixtures (prepareAggregate) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import qualified Data.Set as Set
import GHC.ResponseFile (escapeArgs)
import FixtureSupport (run, hashes, writeJson, splitTab, readInteger)
import System.Directory (createDirectoryIfMissing, doesFileExist, findExecutable, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import qualified System.Info as Host
import Text.Read (readMaybe)

directNames, callNames :: [String]
directNames = ["quotRemInt", "quotRemWord", "addIntC", "subIntC", "plusWord2", "timesWord2", "addWordC", "subWordC", "timesInt2"]
callNames = ["addWordCall", "subWordCall"]

prepareAggregate :: FilePath -> String -> IO Bool
prepareAggregate root "tuple-arithmetic" = prepareTupleArithmetic root >> pure True
prepareAggregate _ _ = pure False

pow2 :: Int -> Integer
pow2 n = 2 ^ n

signed64 :: Integer -> Integer
signed64 value = let residue = value `mod` pow2 64 in
  if residue >= pow2 63 then residue - pow2 64 else residue

-- Signed endpoints, identities, division signs, and the signed multiply
-- overflow boundary. Every native result is also checked with BigInteger.
basePairs :: Set.Set (Integer,Integer)
basePairs = Set.fromList $
  [(x,y) | x <- [-pow2 63, -1, 0, 1, pow2 63 - 1],
           y <- [-pow2 63, -1, 0, 1, pow2 63 - 1]] ++
  [(x,y) | x <- [-13,13], y <- [-5,5]] ++
  [(-pow2 63 + 1,-1), (pow2 32 - 1,pow2 32 + 1), (pow2 32,pow2 32)] ++
  [(x,y) | x <- [3037000499,3037000500], y <- [-x,x]]

wordPairs :: Set.Set (Integer,Integer)
wordPairs = Set.union basePairs $ Set.fromList
  [(signed64 x,signed64 y) | bit <- [0,31,32,63],
    let value = pow2 bit,
    (x,y) <- [(value-1,1), (value,1), (value,value), (value-1,value),
              (pow2 64-value,value), (pow2 64-value,value+1),
              (value-1,pow2 64-value+1), (value,value-1), (0,value), (value,0)]]

requests :: [String] -> [(String,Integer,Integer)]
requests names = [(name,x,y) | name <- names,
  (x,y) <- Set.toAscList (if name `elem` ["addWordC", "subWordC"] ++ callNames then wordPairs else basePairs),
  not (name `elem` ["quotRemInt", "quotRemWord"] &&
    (y == 0 || (name == "quotRemInt" && (x,y) == (-pow2 63,-1))))]

oracleDriver :: String
oracleDriver = unlines $
  ["{-# LANGUAGE MagicHash #-}", "module Main where",
   "import GHC.Exts (Int(I#), Int#)", "import qualified TupleArithmeticAudit as P",
   "emit :: String -> (Int# -> Int# -> Int# -> Int#) -> Int -> Int -> IO ()",
   "emit n f x@(I# a) y@(I# b) = putStrLn (n ++ \"\\t\" ++ show x ++ \"\\t\" ++ show y ++ \"\\t\" ++ show (I# (f a b 0#)) ++ \"\\t\" ++ show (I# (f a b 1#)) ++ if n == \"timesInt2\" then \"\\t\" ++ show (I# (f a b 2#)) else \"\")",
   "dispatch :: [String] -> IO ()", "dispatch [name,x,y] = case name of"] ++
  ["  " ++ show name ++ " -> emit name P." ++ name ++ " (read x) (read y)" | name <- directNames ++ callNames] ++
  ["  _ -> error \"unknown primitive\"", "dispatch _ = error \"invalid input\"",
   "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

verifyRows :: [(String,Integer,Integer)] -> String -> IO Int
verifyRows expected output = do
  let parse line = case splitTab (takeWhile (/= '\r') line) of
        name:x:y:fields | length fields == (if name == "timesInt2" then 3 else 2) -> do
          xx <- readInteger x; yy <- readInteger y
          _ <- traverse readInteger fields
          pure (name,xx,yy)
        _ -> Nothing
  actual <- maybe (die "Malformed native tuple arithmetic row") pure (traverse parse (lines output))
  unless (length actual == length expected && Set.fromList actual == Set.fromList expected)
    (die "Native tuple arithmetic oracle omitted, duplicated, or added an input")
  pure (length actual)

prepareTupleArithmetic :: FilePath -> IO ()
prepareTupleArithmetic root = do
  let directory = "build/tuple-arithmetic"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/TupleArithmeticAudit.hs"
      windows = Host.os == "mingw32"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  exporter <- if windows then maybe "powershell.exe" id <$> findExecutable "pwsh" else pure "bin/export-core.sh"
  python <- if windows then maybe "python" id <$> lookupEnv "THC_PYTHON" else pure "python3"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (takeWhile (/= '\n') version == "9.14.1") (die "Tuple arithmetic requires GHC 9.14.1")
  ghcInfo <- run root [] ghc ["--info"] ""
  case readMaybe ghcInfo :: Maybe [(String,String)] of
    Just info | lookup "target word size" info == Just "8" -> pure ()
    _ -> die "Tuple arithmetic requires the supported 64-bit target"
  forM_ ["pre","post"] $ \stage -> do
    let core = directory </> stage ++ "-core"
        ghcOut = directory </> stage ++ "-ghc"
        options = if stage == "post" then ["-fplugin-opt=THC.Plugin:post-tidy"] else []
    exportArgs <- if windows then do
      let response = directory </> stage ++ "-export.args"
      writeFile (root </> response) (escapeArgs (options ++ [source]))
      pure ["-NoProfile", "-File", root </> "bin/export-core.ps1", "@" ++ (root </> response)]
      else pure (options ++ [source])
    _ <- run root [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> ghcOut)]
      exporter exportArgs ""
    let modulePath = root </> core </> "TupleArithmeticAudit.cbd"
    exists <- doesFileExist modulePath
    unless exists (die ("Missing GHC Core export: " ++ modulePath))
    let audit = directory </> stage ++ "-audit.json"
        auditArgs = concatMap (\name -> ["--entry", "main:TupleArithmeticAudit." ++ name]) (directNames ++ callNames) ++
          ["--output",audit,core </> "TupleArithmeticAudit.cbd"]
    _ <- run root [] python ("bin/audit-core.py" : auditArgs) ""
    pure ()
  let driver = directory </> "NativeTupleArithmetic.hs"
      native = directory </> "native"
      executable = native </> ("tuple-arithmetic-oracle" ++ if windows then ".exe" else "")
  writeFile (root </> driver) oracleDriver
  createDirectoryIfMissing True (root </> native)
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "t/fixtures/compiler", "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", root </> executable] ""
  counts <- forM [("oracle.tsv",directNames),("call-oracle.tsv",callNames)] $ \(filename,names) -> do
    let wanted = requests names
        stdinText = unlines [name ++ "\t" ++ show x ++ "\t" ++ show y | (name,x,y) <- wanted]
    actual <- run root [] (root </> executable) [] stdinText
    count <- verifyRows wanted actual
    writeFile (output </> filename) actual
    pure (filename,count)
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let inputs = sort $ [source, "thc.cabal", "t/haskell-fixtures/AggregateFixtures.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "bin/audit-core.py", "bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json", "bin/build-compiler.sh",
        "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"] ++
        (if windows then ["bin/export-core.ps1", "bin/windows-common.ps1"] else [])
      artifacts = [directory </> stage ++ suffix | stage <- ["pre","post"],
        suffix <- ["-core/TupleArithmeticAudit.cbd","-audit.json"]] ++
        [directory </> file | file <- ["oracle.tsv","call-oracle.tsv","NativeTupleArithmetic.hs"]] ++
        [executable] ++ [directory </> stage ++ "-export.args" | windows, stage <- ["pre","post"]]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest (object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "wordBits" .= (64 :: Int),
     "ghcInfo" .= ghcInfo, "entries" .= directNames, "mixedReturnEntries" .= callNames,
     "stages" .= (["pre","post"] :: [String]), "nativeRows" .= counts,
     "excludedDivisionInputs" .= ("zero divisors and quotRemInt# minBound / -1; no numeric oracle claimed" :: String),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes])
  putStrLn ("tuple-arithmetic: 9 primitives and 2 mixed-result calls, " ++ show counts ++ " native rows")
