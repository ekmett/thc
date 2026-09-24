-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module WordFloatingFixtures (prepareWordFloating) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.List (sort)
import qualified Data.Set as Set
import FixtureSupport (hashes, readInteger, run, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

-- Enumerate inputs, not expected results. Include both tie parities, neighbors,
-- and Float midpoints +/-1 that expose a Double intermediary's double rounding.
inputs :: [Integer]
inputs = Set.toAscList $ Set.fromList $ filter (\x -> x >= 0 && x < 2^ (64 :: Int)) $
  [0,1,2,3,2^ (64 :: Int)-1] ++
  [2^bit + delta | bit <- [1..63 :: Int], delta <- [-1,0,1]] ++
  [2^bit + oddPart * 2^(bit-precision) + delta |
    precision <- [24,53], bit <- [precision..63 :: Int], oddPart <- [1,3,5], delta <- [-1,0,1]]

prepareWordFloating :: FilePath -> IO ()
prepareWordFloating root = do
  let directory = "build/word-floating"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/WordFloatingAudit.hs"
      driver = "compiler/test-fixtures/WordFloatingNative.hs"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Word floating fixtures require GHC 9.14.1")
  ghcInfo <- run root [] ghc ["--info"] ""
  case readMaybe ghcInfo :: Maybe [(String,String)] of
    Just info | lookup "target word size" info == Just "8",
                Just host <- lookup "Host platform" info,
                Just target <- lookup "Target platform" info, host == target -> pure ()
    _ -> die "Word floating fixtures require native 64-bit GHC"
  forM_ ["pre","post"] $ \stage -> do
    let core = directory </> stage ++ "-core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT",root </> core),("THC_GHC_OUT",output </> stage ++ "-ghc")]
      "compiler/export.sh" (options ++ [source]) ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures","-odir",native,"-hidir",native,
    root </> driver,"-o",native </> "word-floating-oracle"] ""
  actual <- run root [] (native </> "word-floating-oracle") [] (unlines (map show inputs))
  let parse line = case splitTab line of
        [x,f,d] -> do
          value <- readInteger x
          floatBits <- readInteger f
          doubleBits <- readInteger d
          if floatBits >= 0 && floatBits < 2^(32 :: Int) && doubleBits >= 0 && doubleBits < 2^(64 :: Int)
            then Just value else Nothing
        _ -> Nothing
  unless (traverse parse (lines actual) == Just inputs) (die "Malformed native word-floating rows")
  writeFile (output </> "oracle.tsv") actual
  forM_ ["pre","post"] $ \stage -> do
    _ <- run root [] "python3" ["scripts/audit-core.py","--entry","wordFloat","--entry","wordDouble",
      "--output",directory </> stage ++ "-audit.json",directory </> stage ++ "-core/WordFloatingAudit.json"] ""
    pure ()
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/WordFloatingFixtures.hs",
        "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = (directory </> "oracle.tsv") : [directory </> stage ++ suffix | stage <- ["pre","post"],
        suffix <- ["-core/WordFloatingAudit.json","-audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),
    "ghcInfo" .= ghcInfo,"wordBits" .= (64 :: Int),"nativeRows" .= length inputs,
    "entries" .= (["wordFloat","wordDouble"] :: [String]),"stages" .= (["pre","post"] :: [String]),
    "inputHashes" .= sourceHashes,"artifactHashes" .= artifactHashes,
    "installedArtifactsHashed" .= False]
  putStrLn ("word-floating: " ++ show (length inputs) ++ " native inputs, two conversions, strict pre/post Core")
