-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Explicit64ArrayFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for explicit64 array.
module Explicit64ArrayFixtures (prepareExplicit64Array) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import FixtureSupport (hashes, readInteger, run, runWithTimeout, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

-- Signed host words include zero, bounds, and bit patterns with both halves set.
inputs :: [Integer]
inputs = [0,1,-1,127,-128,0x123456789abcdef0,-0x123456789abcdef0,
  0x7fffffffffffffff,-0x8000000000000000,0x0000000100000000]

prepareExplicit64Array :: FilePath -> IO ()
prepareExplicit64Array root = do
  let directory = "build/explicit64-arrays"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "test/fixtures/compiler/Explicit64ArrayAudit.hs"
      driver = "test/fixtures/compiler/Explicit64ArrayNative.hs"
      entry = "explicit64ArrayBits"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Explicit64 ByteArray fixtures require GHC 9.14.1")
  forM_ ["pre","post"] $ \stage -> do
    let core = directory </> stage ++ "/core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT",root </> core),
      ("THC_GHC_OUT",output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    _ <- run root [] "python3" ["bin/audit-core.py","--entry",entry,
      "--output",directory </> stage </> "audit.json",
      core </> "Explicit64ArrayAudit.json"] ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "test/fixtures/compiler","-odir",native,"-hidir",native,
    root </> driver,"-o",native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle") []
    (unlines (map show inputs))
  let parse line = case splitTab line of
        [bits,a,b,c,d] -> do
          numbers <- traverse readInteger [bits,a,b,c,d]
          case numbers of
            [x,w,y,z,v] | x == w && x == y && x == z && x == v -> Just x
            _ -> Nothing
        _ -> Nothing
  unless (traverse parse (lines actual) == Just inputs) (die "Malformed native explicit64 ByteArray rows")
  writeFile (output </> "oracle.tsv") actual
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/Explicit64ArrayFixtures.hs",
        "bin/audit-core.py","bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = (directory </> "oracle.tsv") : [directory </> stage </> suffix |
        stage <- ["pre","post"], suffix <- ["core/Explicit64ArrayAudit.json","audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length inputs,"entries" .= ([entry] :: [String]),
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes,"installedArtifactsHashed" .= False]
  putStrLn ("explicit64-arrays: " ++ show (length inputs) ++
    " native rows, six typed primops, strict pre/post Core")
