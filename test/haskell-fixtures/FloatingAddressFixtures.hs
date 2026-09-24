-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
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
      source = "compiler/test-fixtures/FloatingAddressAudit.hs"
      driver = "compiler/test-fixtures/FloatingAddressNative.hs"
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
      "compiler/export.sh" (options ++ [source]) ""
    _ <- run root [] "python3" ["scripts/audit-core.py","--entry",entry,
      "--output",directory </> stage </> "audit.json",
      core </> "FloatingAddressAudit.json"] ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures","-odir",native,"-hidir",native,
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
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/FloatingAddressFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = (directory </> "oracle.tsv") : [directory </> stage </> suffix |
        stage <- ["pre","post"], suffix <- ["core/FloatingAddressAudit.json","audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length inputs,"entries" .= ([entry] :: [String]),
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes,"installedArtifactsHashed" .= False]
  putStrLn ("floating-address: " ++ show (length inputs) ++
    " native rows, six typed primops, strict pre/post Core")
