-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module SqrtFixtures (prepareSqrt) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import Data.Bits ((.|.))
import qualified Data.ByteString as BS
import Data.List (isSuffixOf, nub, sort)
import GHC.Float (castDoubleToWord64, castFloatToWord32)
import FixtureSupport (hashes, readInteger, run, runWithTimeout, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

mathNames :: [String]
mathNames = [operation ++ precision | operation <-
  ["fabs", "exp", "expm1", "log", "log1p", "sin", "cos", "tan", "asin", "acos",
   "atan", "sinh", "cosh", "tanh", "power"], precision <- ["Float", "Double"]]

entries :: [String]
entries = ["sqrtFloat", "sqrtDouble", "floatCase", "doubleCase"] ++ mathNames

integerInputs :: [Integer]
integerInputs = [0,1,2,3,4,15,16,17,81,65535,65536,2^ (20 :: Int),2^ (24 :: Int)]

-- The producer selects bit patterns; the independent exact-rounding model lives
-- in SqrtPrimitiveTest. GHC, not this generator, supplies the expected results.
bitInputs :: Int -> [Integer]
bitInputs width = nub (edges ++ map (.|. sign) (drop 2 edges) ++ randomPositive ++ randomNegative)
  where
    fraction :: Int
    fraction = if width == 32 then 23 else 52
    exponentBits :: Int
    exponentBits = if width == 32 then 8 else 11
    bias = if width == 32 then 127 else 1023 :: Int
    sign = 2 ^ (width - 1)
    infinity = (2 ^ exponentBits - 1) * 2 ^ fraction
    one = toInteger bias * 2 ^ fraction
    four = toInteger (bias + 2) * 2 ^ fraction
    edges = [0,sign,1,2,3,2 ^ fraction - 1,2 ^ fraction,2 ^ fraction + 1,
             one - 1,one,one + 1,four - 1,four,four + 1,infinity - 1,infinity,
             infinity + 2 ^ (fraction - 1),infinity + 1,
             infinity + 2 ^ (fraction - 1) + 12345]
    step value = (value * 6364136223846793005 + 1442695040888963407) `mod` 2 ^ (64 :: Int)
    samples = drop 1 (iterate step (0x53515254 + toInteger width))
    randomPositive = map (`mod` infinity) (take 128 samples)
    randomNegative = map ((.|. sign) . (`mod` infinity)) (take 32 (drop 128 samples))

mathValues :: String -> [Double]
mathValues name
  | prefix "fabs" = [-inf,-3,-0,0,3,inf,nan]
  | prefix "log1p" = [-2,-1,-0.5,-0.25,-0,0,0.25,0.5,1,3,inf,nan]
  | prefix "log" = [-1,-0.5,-0,0,0.125,0.25,0.5,1,1.5,2,3,inf,nan]
  | prefix "power" = [-1,-0.5,-0,0,0.125,0.25,0.5,1,1.5,2,3,nan]
  | prefix "asin" || prefix "acos" = [-2,-1,-0.75,-0.5,-0,0,0.5,0.75,1,2,nan]
  | prefix "sinh" || prefix "cosh" =
      let limits = if "Float" `isSuffixOf` name then [88,89,90] else [709,710,711]
      in [-inf] ++ map negate (reverse limits) ++ [-3,-1,-0,0,1,3] ++ limits ++ [inf,nan]
  | otherwise = [-inf,-3,-2,-1,-0.5,-0.25,-0,0,0.25,0.5,1,2,3,inf,nan]
  where
    prefix text = take (length text) name == text
    inf = 1 / 0
    nan = 0 / 0

inputRows :: [(String,Integer)]
inputRows = [(name,bits) | (name,width) <- [("sqrtFloat",32),("sqrtDouble",64)],
                            bits <- bitInputs width] ++
            [(name,value) | name <- ["floatCase","doubleCase"], value <- integerInputs] ++
            [(name,bitPattern name value) | name <- mathNames, value <- mathValues name]
  where
    bitPattern name value
      | "Float" `isSuffixOf` name = toInteger (castFloatToWord32 (realToFrac value))
      | otherwise = toInteger (castDoubleToWord64 value)

checkRows :: String -> IO ()
checkRows output = do
  let parse line = case splitTab line of
        [name,input,result] -> do
          inputBits <- readInteger input
          resultBits <- readInteger result
          pure (name,inputBits,resultBits)
        _ -> Nothing
  rows <- maybe (die "Malformed sqrt native output") pure (traverse parse (lines output))
  unless (map (\(name,input,_) -> (name,input)) rows == inputRows) (die "Incomplete sqrt native input domain")
  forM_ rows $ \(name,_,result) ->
    unless (result >= 0 && result < 2 ^ (if "Float" `isSuffixOf` name then 32 else 64 :: Int) ||
            name `elem` ["floatCase","doubleCase"] && result >= 0) (die "Invalid sqrt native result bits")

prepareSqrt :: FilePath -> IO ()
prepareSqrt root = do
  let directory = "build/sqrt"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/SqrtAudit.hs"
      driver = "compiler/test-fixtures/SqrtAuditNative.hs"
      native = output </> "native"
  createDirectoryIfMissing True native
  -- Retire receipts from the former Python producer on persistent runners.
  forM_ [manifest, output </> "provenance.json", output </> "checks.json"] $ \receipt -> do
    present <- doesFileExist receipt
    when present (removeFile receipt)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Sqrt fixtures require GHC 9.14.1")
  ghcInfo <- run root [] ghc ["--info"] ""
  writeFile (output </> "inputs.tsv") (unlines [name ++ "\t" ++ show bits | (name,bits) <- inputRows])
  _ <- run root [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-icompiler/test-fixtures","-odir",native,"-hidir",native,driver,"-o",native </> "oracle"] ""
  rows <- runWithTimeout (Just 120000000) root [] (native </> "oracle") [output </> "inputs.tsv"] ""
  checkRows rows
  writeFile (output </> "oracle.tsv") rows
  writeFile (output </> "integer-oracle.tsv")
    (unlines [row | row <- lines rows, "floatCase\t" `prefixOf` row || "doubleCase\t" `prefixOf` row])
  forM_ ["pre","post"] $ \stage -> do
    _ <- run root [("THC_CORE_OUT",output </> stage ++ "-core"),
                   ("THC_GHC_OUT",output </> stage ++ "-ghc"),("THC_SOURCE_NOTES","true")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source]) ""
    _ <- run root [] "python3" (["scripts/audit-core.py",directory </> stage ++ "-core/SqrtAudit.json"] ++
      concatMap (\name -> ["--entry",name]) entries ++
      ["--output",directory </> stage ++ "-audit.json"]) ""
    evidence <- BS.readFile (output </> stage ++ "-audit.json")
    case decodeStrict' evidence of
      Just (Object fields) | KeyMap.lookup "accepted" fields == Just (Bool True) -> pure ()
      _ -> die ("Strict sqrt audit rejected " ++ stage)
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/SqrtFixtures.hs",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json","scripts/generate-scalar-signatures.py"] ++
        ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> name | name <- ["inputs.tsv","oracle.tsv","integer-oracle.tsv",
        "native/oracle"]] ++
        [directory </> stage ++ suffix | stage <- ["pre","post"],
          suffix <- ["-core/SqrtAudit.json","-audit.json"]]
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "ghcInfo" .= ghcInfo,"nativeRows" .= length (lines rows),"entries" .= entries,
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= inputHashes,
    "artifactHashes" .= artifactHashes,"installedArtifactsHashed" .= False]
  putStrLn ("sqrt: " ++ show (length (lines rows)) ++ " native rows; strict pre/post Core")
  where
    prefixOf prefix value = take (length prefix) value == prefix
