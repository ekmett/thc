-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (092 floating-address)
-- Purpose: Check Float/Double address loads and stores retain numeric/bit results.
-- Produces/consumed result: CBDs and oracle.tsv.
-- Cost and overlap: Keep the address boundary; shared widths are covered by general memory suites.
--   Another full acquisition pipeline is not warranted.
-- Build status: Value review only; admission still requires explicit inputs and single-
--   owner outputs.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 092.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : FloatingAddressFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for floating address.
module FloatingAddressFixtures (prepareFloatingAddress) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import FixtureSupport (hashes, readInteger, run, runWithTimeout, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

-- Raw IEEE patterns. Quiet NaNs are included; signaling NaN canonicalization
-- is not guaranteed by the JVM Float/Double carrier.
inputs :: [(Integer,Integer)]
inputs =
  [(0,0), (0x80000000,0x8000000000000000),
   (0x3f800000,0x3ff0000000000000), (0xbf800000,0xbff0000000000000),
   (0x7f800000,0x7ff0000000000000), (0xff800000,0xfff0000000000000),
   (0x7fc01234,0x7ff8000000001234), (0x00000001,0x0000000000000001),
   (0x007fffff,0x000fffffffffffff), (0x7f7fffff,0x7fefffffffffffff)]

prepareFloatingAddress :: FilePath -> IO ()
prepareFloatingAddress root = do
  let directory = "build/floating-address"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/FloatingAddressAudit.hs"
      driver = "t/fixtures/compiler/FloatingAddressNative.hs"
      entry = "floatingAddressBits"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Floating Addr fixtures require GHC 9.14.1")
  forM_ ["pre","post"] $ \stage -> do
    let core = directory </> stage ++ "/core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT",root </> core),
      ("THC_GHC_OUT",output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    _ <- run root [] "python3" ["bin/audit-core.py","--entry","main:FloatingAddressAudit." ++ entry,
      "--output",directory </> stage </> "audit.json",
      core </> "FloatingAddressAudit.cbd"] ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "t/fixtures/compiler","-odir",native,"-hidir",native,
    root </> driver,"-o",native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle") []
    (unlines [show f ++ " " ++ show d | (f,d) <- inputs])
  let parse line = case splitTab line of
        [f,d,iF,rF,iD,rD] -> do
          numbers <- traverse readInteger [f,d,iF,rF,iD,rD]
          case numbers of
            [a,b,c,e,g,h] | a >= 0 && a < 2^(32 :: Int) &&
              b >= 0 && b < 2^(64 :: Int) && c == a && e == a && g == b && h == b ->
                Just (a,b)
            _ -> Nothing
        _ -> Nothing
  unless (traverse parse (lines actual) == Just inputs) (die "Malformed native floating Addr rows")
  writeFile (output </> "oracle.tsv") actual
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source,driver,"thc.cabal","t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/FloatingAddressFixtures.hs",
        "bin/audit-core.py","bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = (directory </> "oracle.tsv") : [directory </> stage </> suffix |
        stage <- ["pre","post"], suffix <- ["core/FloatingAddressAudit.cbd","audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length inputs,"entries" .= ([entry] :: [String]),
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes,"installedArtifactsHashed" .= False]
  putStrLn ("floating-address: " ++ show (length inputs) ++
    " native rows, six typed primops, strict pre/post Core")
