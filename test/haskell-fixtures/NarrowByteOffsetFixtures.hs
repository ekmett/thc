-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module NarrowByteOffsetFixtures (prepareNarrowByteOffset) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import FixtureSupport (hashes, readInteger, run, runWithTimeout, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

-- Signed and unsigned high-bit, wraparound, and adjacent-boundary inputs.
-- The JVM test owns the independent native-endian 16-bit byte model.
inputs :: [(Integer,Integer)]
inputs =
  [(-32768,32768), (-1,65535), (0,0), (1,1), (32767,32767),
   (32768,65536), (-32769,65537), (65535,255), (0x12345,0xabcd),
   (-0x12345,0x12345)]

prepareNarrowByteOffset :: FilePath -> IO ()
prepareNarrowByteOffset root = do
  let directory = "build/narrow-byte-offset"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/NarrowByteOffsetAudit.hs"
      driver = "compiler/test-fixtures/NarrowByteOffsetNative.hs"
      entry = "narrowByteOffsetValues"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Narrow byte-offset fixtures require GHC 9.14.1")
  forM_ ["pre","post"] $ \stage -> do
    let core = directory </> stage ++ "/core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT",root </> core),
      ("THC_GHC_OUT",output </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source]) ""
    _ <- run root [] "python3" ["scripts/audit-core.py","--entry",entry,
      "--output",directory </> stage </> "audit.json",
      core </> "NarrowByteOffsetAudit.json"] ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make","-O2","-dynamic","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures","-odir",native,"-hidir",native,
    root </> driver,"-o",native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle") []
    (unlines [show f ++ " " ++ show d | (f,d) <- inputs])
  let parse line = case splitTab line of
        [signedText,unsignedText,indexSigned,readSigned,indexUnsigned,readUnsigned] -> do
          numbers <- traverse readInteger [signedText,unsignedText,indexSigned,readSigned,indexUnsigned,readUnsigned]
          case numbers of
            [a,b,c,e,g,h] | b >= 0 && c >= -32768 && c <= 32767 &&
              e >= -32768 && e <= 32767 && g >= 0 && g <= 65535 && h >= 0 && h <= 65535 ->
                Just (a,b)
            _ -> Nothing
        _ -> Nothing
  unless (traverse parse (lines actual) == Just inputs) (die "Malformed native narrow byte-offset rows")
  writeFile (output </> "oracle.tsv") actual
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/NarrowByteOffsetFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = (directory </> "oracle.tsv") : [directory </> stage </> suffix |
        stage <- ["pre","post"], suffix <- ["core/NarrowByteOffsetAudit.json","audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "nativeRows" .= length inputs,"entries" .= ([entry] :: [String]),
    "stages" .= (["pre","post"] :: [String]),"inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes,"installedArtifactsHashed" .= False]
  putStrLn ("narrow-byte-offset: " ++ show (length inputs) ++
    " native rows, six signed/unsigned typed primops, strict pre/post Core")
