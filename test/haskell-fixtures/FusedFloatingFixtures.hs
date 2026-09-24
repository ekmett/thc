-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module FusedFloatingFixtures (prepareFusedFloating) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (object, (.=))
import Data.Bits (xor)
import Data.List (sort)
import qualified Data.Set as Set
import FixtureSupport (hashes, readInteger, run, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

-- Input bits only; results come from native GHC, never a producer-side FP model.
inputs :: Int -> [(Integer,Integer,Integer)]
inputs width = Set.toAscList $ Set.fromList $
  [(x,y,z) | x <- edges, y <- edges, z <- [0,sign]] ++
  concat [[(x,one,z),(one,x,z),(one,z,x)] | x <- edges, z <- [one,one+sign]] ++
  [(x `xor` sx,y `xor` sy,z `xor` sz) | (x,y,z) <- cancellation,
    sx <- [0,sign], sy <- [0,sign], sz <- [0,sign]]
  where
    fraction :: Int
    fraction = if width == 32 then 23 else 52
    bias :: Integer
    bias = if width == 32 then 127 else 1023
    sign = 2^(width-1)
    one = bias * 2^fraction
    infinity = (2*bias+1) * 2^fraction
    edges = [x+s | x <- [0,1,2^fraction-1,2^fraction,one-2,one-1,one,one+1,one+2,
                        infinity-1,infinity,infinity+1,infinity+2^(fraction-1)+123], s <- [0,sign]]
    cancellation = [(one+1,one-2,one+sign),(infinity-1,one+2^fraction,infinity-1+sign),
      (1,one-2^fraction,1),(2^fraction,one-1,2^fraction+sign),(one+2^(fraction-1),one+3,1)]

entries :: Int -> [String]
entries width = ["fused" ++ (if width == 32 then "Float" else "Double") ++ operation |
  operation <- ["Add","Sub","NegAdd","NegSub"]]

prepareFusedFloating :: FilePath -> IO ()
prepareFusedFloating root = do
  let directory = "build/fused-floating"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/FloatingAudit.hs"
      driver = "compiler/test-fixtures/FloatingAuditNative.hs"
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  infoText <- run root [] ghc ["--info"] ""
  unless (lines version == ["9.14.1"]) (die "Fused floating requires GHC 9.14.1")
  case readMaybe infoText :: Maybe [(String,String)] of
    Just info | lookup "target word size" info == Just "8",
                Just host <- lookup "Host platform" info,
                Just target <- lookup "Target platform" info, host == target -> pure ()
    _ -> die "Fused floating requires native 64-bit GHC"
  forM_ ["pre","post"] $ \stage -> do
    _ <- run root [("THC_CORE_OUT",output </> stage ++ "-core"),("THC_GHC_OUT",output </> stage ++ "-ghc")]
      "compiler/export.sh" (["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++ [source]) ""
    pure ()
  let native = output </> "native"
  createDirectoryIfMissing True native
  -- No ISA flags: pinned GHC's standard-library FMA fallback is the oracle.
  _ <- run root [] ghc ["--make","-O2","-fforce-recomp","-dcore-lint","-dstg-lint",
    "-icompiler/test-fixtures","-odir",native,"-hidir",native,driver,"-o",native </> "floating-oracle"] ""
  let cases = [(width,x,y,z) | width <- [32,64], (x,y,z) <- inputs width]
      stdinRows = unlines [unwords (map show [toInteger width,x,y,z]) | (width,x,y,z) <- cases]
      expected = [(name,x,y,z,width) | (width,x,y,z) <- cases, name <- entries width]
  actual <- run root [] (native </> "floating-oracle") ["--fused"] stdinRows
  let parse line = case splitTab line of
        [name,x,y,z,result] -> do
          a <- readInteger x; b <- readInteger y; c <- readInteger z; r <- readInteger result
          pure (name,a,b,c,r)
        _ -> Nothing
  rows <- maybe (die "Malformed fused native output") pure (traverse parse (lines actual))
  unless (length rows == length expected && and
    [name == want && (x,y,z) == (a,b,c) && r >= 0 && r < 2^width |
      ((name,x,y,z,r),(want,a,b,c,width)) <- zip rows expected]) (die "Incomplete fused native domain")
  writeFile (output </> "oracle.tsv") actual
  forM_ ["pre","post"] $ \stage -> do
    _ <- run root [] "python3" (["scripts/audit-core.py",directory </> stage ++ "-core/FloatingAudit.json",
      "--output",directory </> stage ++ "-audit.json"] ++ concatMap (\name -> ["--entry",name]) (entries 32 ++ entries 64)) ""
    pure ()
  plugins <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source,driver,"thc.cabal","test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs","test/haskell-fixtures/FusedFloatingFixtures.hs",
        "compiler/build.sh","compiler/export.sh","compiler/toolchain.sh","compiler/plugin.py",
        "scripts/audit-core.py","scripts/core-capabilities.json","src/main/resources/thc/scalar-primop-signatures.json"] ++
        ["compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.tsv"] ++ [directory </> stage ++ suffix | stage <- ["pre","post"],
        suffix <- ["-core/FloatingAudit.json","-audit.json"]]
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int),"ghc" .= ("9.14.1" :: String),"ghcInfo" .= infoText,
    "installedArtifactsHashed" .= False,"nativeFlags" .= (["-O2","-fforce-recomp","-dcore-lint","-dstg-lint"] :: [String]),
    "entries" .= (entries 32 ++ entries 64),"stages" .= (["pre","post"] :: [String]),
    "nativeRows" .= length rows,"inputHashes" .= inputHashes,"artifactHashes" .= artifactHashes]
  putStrLn ("fused-floating: " ++ show (length rows) ++ " native rows, eight primops, strict pre/post Core")
