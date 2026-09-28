-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : Int32ByteOffsetFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for int32 byte offset.
module Int32ByteOffsetFixtures (prepareInt32ByteOffset) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import FixtureSupport (hashes, readInteger, run, runWithTimeout, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

-- Signed and unsigned high-bit, wraparound, and adjacent-boundary inputs.
-- The JVM test owns the independent native-endian 32-bit byte model.
inputs :: [(Integer,Integer)]
inputs =
  [(-2147483648,2147483648), (-1,4294967295), (0,0), (1,1), (2147483647,2147483647),
   (2147483648,4294967296), (-2147483649,4294967297), (4294967295,255), (0x123456789,0xabcdef01),
   (-0x123456789,0x123456789)]

prepareInt32ByteOffset :: FilePath -> IO ()
prepareInt32ByteOffset root = do
  let directory = "build/int32-byte-offset"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/compiler/Int32ByteOffsetAudit.hs"
      driver = "t/fixtures/compiler/Int32ByteOffsetNative.hs"
      entry = "int32ByteOffsetValues"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Int32 byte-offset fixtures require GHC 9.14.1")
  forM_ ["pre","post"] $ \stage -> do
    let core = directory </> stage ++ "/core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT",root </> core),
      ("THC_GHC_OUT",output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    _ <- run root [] "python3" ["bin/audit-core.py","--entry",entry,
      "--output",directory </> stage </> "audit.json",
      core </> "Int32ByteOffsetAudit.json"] ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "t/fixtures/compiler","-odir",native,"-hidir",native,
    root </> driver,"-o",native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle") []
    (unlines [show f ++ " " ++ show d | (f,d) <- inputs])
  let parse line = case splitTab line of
        [signedText,unsignedText,indexSigned,readSigned,indexUnsigned,readUnsigned] -> do
          numbers <- traverse readInteger [signedText,unsignedText,indexSigned,readSigned,indexUnsigned,readUnsigned]
          case numbers of
            [a,b,c,e,g,h] | b >= 0 && c >= -2147483648 && c <= 2147483647 &&
              e >= -2147483648 && e <= 2147483647 && g >= 0 && g <= 4294967295 && h >= 0 && h <= 4294967295 ->
                Just (a,b)
            _ -> Nothing
        _ -> Nothing
  unless (traverse parse (lines actual) == Just inputs) (die "Malformed native int32 byte-offset rows")
  writeFile (output </> "oracle.tsv") actual
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source,driver,"thc.cabal","t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs","t/haskell-fixtures/Int32ByteOffsetFixtures.hs",
        "bin/audit-core.py","bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh","bin/export-core.sh","bin/toolchain.sh","bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = (directory </> "oracle.tsv") : [directory </> stage </> suffix |
        stage <- ["pre","post"], suffix <- ["core/Int32ByteOffsetAudit.json","audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length inputs,"entries" .= ([entry] :: [String]),
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes,"installedArtifactsHashed" .= False]
  putStrLn ("int32-byte-offset: " ++ show (length inputs) ++
    " native rows, six signed/unsigned typed primops, strict pre/post Core")
