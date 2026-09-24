-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module AggregateFixtures (prepareAggregate) where

import Control.Monad (forM, forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.Bits ((.&.), xor, shiftL, shiftR)
import Data.List (sort)
import qualified Data.Set as Set
import FixtureSupport (run, hashes, writeJson, splitTab, readInteger)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

directNames, callNames :: [String]
directNames = ["quotRemInt", "quotRemWord", "addIntC", "subIntC", "plusWord2", "timesWord2", "addWordC", "subWordC"]
callNames = ["addWordCall", "subWordCall"]

prepareAggregate :: FilePath -> String -> IO Bool
prepareAggregate root "tuple-arithmetic" = prepareTupleArithmetic root >> pure True
prepareAggregate _ _ = pure False

pow2 :: Int -> Integer
pow2 n = 2 ^ n

signed64 :: Integer -> Integer
signed64 value = let residue = value `mod` pow2 64 in
  if residue >= pow2 63 then residue - pow2 64 else residue

-- Deterministic probes around signed endpoints, word carry boundaries, and
-- neighboring bits. The JVM suites check every native result with BigInteger.
values :: [Integer]
values = Set.toAscList $ Set.fromList $
  [-pow2 63, -pow2 63 + 1, pow2 63 - 2, pow2 63 - 1, -4097, -1, 0, 1, 4097] ++
  [signed64 (sign * pow2 bit + delta) | bit <- [1,7,8,15,16,31,32,62,63],
    sign <- [-1,1], delta <- [-1,0,1]] ++
  take 32 (map (signed64 . toInteger) (iterate xorshift (9141 :: Integer)))
  where
    xorshift x = let a = x `xor` (x `shiftL` 13)
                     b = a `xor` (a `shiftR` 7)
                 in (b `xor` (b `shiftL` 17)) .&. (pow2 64 - 1)

basePairs :: Set.Set (Integer,Integer)
basePairs = Set.fromList $
  [(x,y) | x <- values, y <- anchors] ++
  [(x,y) | x <- anchors, y <- values] ++
  [(x,signed64 (x + delta)) | x <- values, delta <- [-1,0,1]]
  where anchors = [-pow2 63, pow2 63 - 1, -4097, -1, 0, 1, 4097]

wordPairs :: Set.Set (Integer,Integer)
wordPairs = Set.union basePairs $ Set.fromList
  [(signed64 x,signed64 y) | bit <- [0..63],
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
   "emit n f x@(I# a) y@(I# b) = putStrLn (n ++ \"\\t\" ++ show x ++ \"\\t\" ++ show y ++ \"\\t\" ++ show (I# (f a b 0#)) ++ \"\\t\" ++ show (I# (f a b 1#)))",
   "dispatch :: [String] -> IO ()", "dispatch [name,x,y] = case name of"] ++
  ["  " ++ show name ++ " -> emit name P." ++ name ++ " (read x) (read y)" | name <- directNames ++ callNames] ++
  ["  _ -> error \"unknown primitive\"", "dispatch _ = error \"invalid input\"",
   "main :: IO ()", "main = getContents >>= mapM_ (dispatch . words) . lines"]

verifyRows :: [(String,Integer,Integer)] -> String -> IO Int
verifyRows expected output = do
  let parse line = case splitTab (takeWhile (/= '\r') line) of
        [name,x,y,a,b] -> do
          xx <- readInteger x; yy <- readInteger y
          _ <- readInteger a; _ <- readInteger b
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
      source = "compiler/test-fixtures/TupleArithmeticAudit.hs"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
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
    _ <- run root [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",root </> ghcOut)]
      "compiler/export.sh" (options ++ [source]) ""
    let modulePath = root </> core </> "TupleArithmeticAudit.json"
    exists <- doesFileExist modulePath
    unless exists (die ("Missing GHC Core export: " ++ modulePath))
    let audit = directory </> stage ++ "-audit.json"
        auditArgs = concatMap (\name -> ["--entry",name]) (directNames ++ callNames) ++
          ["--output",audit,core </> "TupleArithmeticAudit.json"]
    _ <- run root [] "python3" ("scripts/audit-core.py" : auditArgs) ""
    pure ()
  let driver = directory </> "NativeTupleArithmetic.hs"
      native = directory </> "native"
      executable = native </> "tuple-arithmetic-oracle"
  writeFile (root </> driver) oracleDriver
  createDirectoryIfMissing True (root </> native)
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures", "-odir", root </> native,
    "-hidir", root </> native, root </> driver, "-o", root </> executable] ""
  counts <- forM [("oracle.tsv",directNames),("call-oracle.tsv",callNames)] $ \(filename,names) -> do
    let wanted = requests names
        stdinText = unlines [name ++ "\t" ++ show x ++ "\t" ++ show y | (name,x,y) <- wanted]
    actual <- run root [] (root </> executable) [] stdinText
    count <- verifyRows wanted actual
    writeFile (output </> filename) actual
    pure (filename,count)
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, "thc.cabal", "test/haskell-fixtures/AggregateFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh",
        "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> stage ++ suffix | stage <- ["pre","post"],
        suffix <- ["-core/TupleArithmeticAudit.json","-audit.json"]] ++
        [directory </> file | file <- ["oracle.tsv","call-oracle.tsv","NativeTupleArithmetic.hs",
          "native/tuple-arithmetic-oracle"]]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson manifest (object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "wordBits" .= (64 :: Int),
     "ghcInfo" .= ghcInfo, "entries" .= directNames, "mixedReturnEntries" .= callNames,
     "stages" .= (["pre","post"] :: [String]), "nativeRows" .= counts,
     "excludedDivisionInputs" .= ("zero divisors and quotRemInt# minBound / -1; no numeric oracle claimed" :: String),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes])
  putStrLn ("tuple-arithmetic: 8 primitives and 2 mixed-result calls, " ++ show counts ++ " native rows")
