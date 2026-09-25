-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module SimdFloatFmaFixtures (prepareSimdFloatFma) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.Bits (xor)
import FixtureSupport (hashes, run, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (arch, os)
import Text.Read (readMaybe)

entries :: [String]
entries = ["addCase", "subCase", "negAddCase", "negSubCase"]

wideEntries :: [String]
wideEntries = ["wideAddCase", "wideSubCase", "wideNegAddCase", "wideNegSubCase"]

doubleEntries :: [String]
doubleEntries = ["doubleAddCase", "doubleSubCase", "doubleNegAddCase", "doubleNegSubCase"]

inputs :: [[Integer]]
inputs = [[0x3f800001 `xor` sx, 0x3f7ffffe `xor` sy, 0xbf800000 `xor` sz] |
  sx <- signs, sy <- signs, sz <- signs] ++
  [[x,y,z] | x <- signs, y <- signs, z <- signs] ++
  [[0x7f7fffff,0x40000000,0xff7fffff], [1,0x3f000000,1],
   [0x3fc00000,0x3f800003,1], [0x7f800000,0,0x3f800000],
   [0x7fc00001,0x3f800000,0], [0x40000000,0x40400000,0x40800000]]
  where signs = [0,0x80000000]

doubleInputs :: [[Integer]]
doubleInputs = [[0x3ff0000000000001 `xor` sx, 0x3feffffffffffffe `xor` sy, 0xbff0000000000000 `xor` sz] |
  sx <- signs, sy <- signs, sz <- signs] ++
  [[x,y,z] | x <- signs, y <- signs, z <- signs] ++
  [[0x7fefffffffffffff,0x4000000000000000,0xffefffffffffffff], [1,0x3fe0000000000000,1],
   [0x3ff8000000000000,0x3ff0000000000003,1], [0x7ff0000000000000,0,0x3ff0000000000000],
   [0x7ff8000000000001,0x3ff0000000000000,0], [0x4000000000000000,0x4008000000000000,0x4010000000000000]]
  where signs = [0,0x8000000000000000]

prepareSimdFloatFma :: FilePath -> IO ()
prepareSimdFloatFma root = do
  let directory = "build/simd-floatx4-fma"
      output = root </> directory
      source = "compiler/test-fixtures/SimdFloatFma.hs"
      driver = "compiler/test-fixtures/SimdFloatFmaNative.hs"
      manifest = output </> "manifest.json"
      exportOnly = arch == "aarch64"
      stages = if exportOnly then ["pre"] else ["pre","post"]
      -- GHC requires AVX2 when lowering the shared FloatX8 256-bit workers.
      nativeFlags = ["-mavx2","-mfma"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "FloatX4 FMA fixture requires GHC 9.14.1")
  unless exportOnly $ do
    unless (arch == "x86_64" && os == "linux") (die "Native FloatX4 FMA oracle requires Linux x86_64")
    cpu <- readFile "/proc/cpuinfo"
    unless (all (`elem` words cpu) ["avx","avx2","fma"]) (die "Native SIMD FMA oracle requires AVX2 and FMA hardware")
  forM_ stages $ \stage -> do
    _ <- run root [("THC_CORE_OUT", output </> stage ++ "-core"),
                  ("THC_GHC_OUT", output </> stage ++ "-ghc")]
      "compiler/export.sh" ((if exportOnly then ["-fno-code","-fwrite-if-simplified-core"] else nativeFlags) ++
        ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source]) ""
    -- Canonical admission: no private capability profile or expected frontier.
    let audit = directory </> stage ++ "-audit.json"
    _ <- runLogged 120 root (directory </> "logs") (stage ++ "-audit") [] "python3"
      (["scripts/audit-core.py", directory </> stage ++ "-core/SimdFloatFma.json", "--output", audit] ++
       concatMap (\entry -> ["--entry",entry]) (entries ++ wideEntries))
    _ <- runLogged 120 root (directory </> "logs") (stage ++ "-double-audit") [] "python3"
      (["scripts/audit-core.py", directory </> stage ++ "-core/SimdFloatFma.json",
        "--output", directory </> stage ++ "-double-audit.json"] ++
       concatMap (\entry -> ["--entry",entry]) doubleEntries)
    pure ()
  let requests = [[entry] ++ map show values ++ [show lane] | entry <- entries, values <- inputs, lane <- [0..3 :: Int]] ++
        [[entry] ++ map show values ++ [show lane] | entry <- wideEntries, values <- inputs, lane <- [0..7 :: Int]] ++
        [[entry] ++ map show values ++ [show lane] | entry <- doubleEntries, values <- doubleInputs, lane <- [0..1 :: Int]]
      binary = output </> "native/oracle"
  rows <- if exportOnly then pure [] else do
    createDirectoryIfMissing True (output </> "native")
    _ <- run root [] ghc (["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
      "-icompiler/test-fixtures","-odir",output </> "native","-hidir",output </> "native",driver,"-o",binary] ++ nativeFlags) ""
    lines <$> run root [] binary [] (unlines (map unwords requests))
  unless exportOnly $ do
    unless (map (take 5 . words) rows == requests && all valid rows) (die "Incomplete FloatX4 FMA native oracle")
    writeFile (output </> "oracle.txt") (unlines rows)
  inputHashes <- hashes root [source,driver,"test/haskell-fixtures/SimdFloatFmaFixtures.hs",
    "scripts/core_vectors.py","scripts/audit-core.py","scripts/core-capabilities.json","compiler/export.sh"]
  artifactHashes <- hashes root ([directory </> "oracle.txt" | not exportOnly] ++
    [directory </> stage ++ suffix | stage <- stages, suffix <- ["-core/SimdFloatFma.json","-audit.json","-double-audit.json"]])
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "entries" .= entries,"wideEntries" .= wideEntries,"inputs" .= inputs,"stages" .= stages,"nativeFlags" .= nativeFlags,
    -- Decimal strings preserve unsigned Word64 bits through the JVM JSON reader.
    "doubleEntries" .= doubleEntries,"doubleInputs" .= map (map show) doubleInputs,
    "nativeRows" .= (if exportOnly then Nothing else Just (length rows)),
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("simd-floatx4-fma: " ++ show (length rows) ++ " shared native rows; FloatX4, FloatX8 and DoubleX2 admitted")
  where
    valid row = case words row of
      [entry,_,_,_,_,result] -> maybe False (\n -> n >= 0 && n <=
        (if entry `elem` doubleEntries then 0xffffffffffffffff else 0xffffffff)) (readMaybe result :: Maybe Integer)
      _ -> False
