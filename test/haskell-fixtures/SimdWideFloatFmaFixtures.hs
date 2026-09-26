-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module SimdWideFloatFmaFixtures (prepareSimdWideFloatFma) where

import Control.Monad (unless, when)
import Data.Aeson (object, (.=))
import FixtureSupport (hashes, run, runLogged, writeJson)
import SimdFloatFmaFixtures (inputs, doubleInputs)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (arch, os)
import Text.Read (readMaybe)

prepareSimdWideFloatFma :: FilePath -> IO ()
prepareSimdWideFloatFma root = do
  let directory = "build/simd-wide-floating-fma"
      output = root </> directory
      source = "compiler/test-fixtures/SimdWideFloatFma.hs"
      driver = "compiler/test-fixtures/SimdWideFloatFmaNative.hs"
      manifest = output </> "manifest.json"
      floatEntries = entries "huge"
      doubleEntries = entries "doubleHuge"
      nativeFlags = ["-mfma" | arch == "x86_64"]
  createDirectoryIfMissing True (output </> "native")
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Wide FMA fixture requires GHC 9.14.1")
  unless (arch == "aarch64" || arch == "x86_64" && os == "linux")
    (die "Native scalar-lane FMA oracle requires AArch64 or Linux x86_64")
  when (arch == "x86_64") $ do
    cpu <- readFile "/proc/cpuinfo"
    unless ("fma" `elem` words cpu) (die "Native scalar-lane FMA oracle requires FMA hardware")
  -- GHC exports real 512-bit primops without executing native wide code.
  -- The pre-Tidy boundary is explicit on every host, never a silent fallback.
  _ <- run root [("THC_CORE_OUT",output </> "pre-core"),("THC_GHC_OUT",output </> "pre-ghc")]
    "compiler/export.sh" ["-fno-code","-fwrite-if-simplified-core",source] ""
  let audit suffix roots = runLogged 120 root (directory </> "logs") suffix [] "python3"
        (["scripts/audit-core.py",directory </> "pre-core/SimdWideFloatFma.json",
          "--output",directory </> ("pre-" ++ suffix ++ ".json")] ++
          concatMap (\entry -> ["--entry",entry]) roots)
  _ <- audit "audit" floatEntries
  _ <- audit "double-audit" doubleEntries
  let requests = [[entry] ++ map show values ++ [show lane] | entry <- floatEntries, values <- inputs, lane <- [0..15::Int]] ++
        [[entry] ++ map show values ++ [show lane] | entry <- doubleEntries, values <- doubleInputs, lane <- [0..7::Int]]
      binary = output </> "native/scalar-lane-oracle"
  _ <- run root [] ghc (["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-odir",output </> "native","-hidir",output </> "native",driver,"-o",binary] ++ nativeFlags) ""
  rows <- lines <$> run root [] binary [] (unlines (map unwords requests))
  let valid row = case words row of
        [entry,_,_,_,_,result] -> maybe False (\n -> n >= 0 && n <=
          (if entry `elem` doubleEntries then 0xffffffffffffffff else 0xffffffff)) (readMaybe result :: Maybe Integer)
        _ -> False
  unless (map (take 5 . words) rows == requests && all valid rows)
    (die "Incomplete wide FMA native scalar-lane oracle")
  writeFile (output </> "oracle.txt") (unlines rows)
  inputHashes <- hashes root [source,driver,"test/haskell-fixtures/SimdWideFloatFmaFixtures.hs",
    "test/haskell-fixtures/SimdFloatFmaFixtures.hs","scripts/core_vectors.py",
    "scripts/audit-core.py","scripts/core-capabilities.json","compiler/export.sh"]
  artifactHashes <- hashes root [directory </> file | file <-
    ["oracle.txt","pre-core/SimdWideFloatFma.json","pre-audit.json","pre-double-audit.json"]]
  writeJson manifest $ object ["schema" .= (1::Int),"ghc" .= ("9.14.1"::String),
    "hugeEntries" .= floatEntries,"doubleHugeEntries" .= doubleEntries,"inputs" .= inputs,
    "doubleInputs" .= map (map show) doubleInputs,"stages" .= (["pre"]::[String]),
    "nativeFlags" .= nativeFlags,"nativeRows" .= length rows,
    "oracleKind" .= ("native-ghc-scalar-fma-lanes"::String),"nativeVectorParity" .= False,
    "inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("simd-wide-floating-fma: " ++ show (length rows) ++ " native scalar-lane observations; native 512-bit instruction parity unproved")
  where entries prefix = [prefix ++ operation ++ "Case" | operation <- ["Add","Sub","NegAdd","NegSub"]]
