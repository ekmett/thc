-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module SmallArrayFixtures (prepareSmallArrays) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (hashes, readInteger, run, runWithTimeout, splitTab, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareSmallArrays :: FilePath -> IO ()
prepareSmallArrays root = do
  let directory = "build/small-arrays"
      native = directory </> "native"
      source = "compiler/test-fixtures/SmallArrayAudit.hs"
      driver = "compiler/test-fixtures/SmallArrayAuditNative.hs"
      binary = native </> "oracle"
      inputs = [0,1,2,3,7,16,31,127,1024] :: [Integer]
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- takeWhile (/= '\n') <$> run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1") (die "SmallArray fixture requires GHC 9.14.1")
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i./compiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", binary] ""
  oracle <- runWithTimeout (Just 30000000) root [] (root </> binary) []
    (unlines (map show inputs))
  let rows = map (splitTab . takeWhile (/= '\r')) (lines oracle)
      parsed = [(x,y) | [a,b] <- rows, Just x <- [readInteger a], Just y <- [readInteger b]]
  unless (length parsed == length inputs && map fst parsed == inputs)
    (die "SmallArray native oracle returned malformed or missing rows")
  writeFile (root </> directory </> "oracle.tsv") oracle
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        report = directory </> stage </> "audit.json"
        modulePath = core </> "SmallArrayAudit.json"
        options = if stage == "post" then ["-fplugin-opt=THC.Plugin:post-tidy"] else []
    createDirectoryIfMissing True (root </> core)
    createDirectoryIfMissing True (root </> ghcOut)
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "compiler/export.sh" (options ++ [source]) ""
    _ <- run root [] "python3" ["scripts/audit-core.py", modulePath, "--entry",
      "smallComposite", "--output", report] ""
    accepted <- BS.readFile (root </> report)
    unless (case decodeStrict' accepted of
      Just (Object fields) -> KeyMap.lookup "accepted" fields == Just (Bool True)
      _ -> False) (die ("SmallArray strict Core audit rejected " ++ stage))
    pure (stage, modulePath, report)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source, driver, "test/haskell-fixtures/SmallArrayFixtures.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh",
        "compiler/plugin.py", "thc.cabal", "cabal.project"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, "core_" `isPrefixOf` file, takeExtension file == ".py"]
      artifacts = [binary, directory </> "oracle.tsv"] ++
        [path | (_,modulePath,report) <- stages, path <- [modulePath, report]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= version, "entry" .= ("smallComposite" :: String),
     "nativeRows" .= length parsed,
     "stages" .= Map.fromList [(stage, path) | (stage,path,_) <- stages],
     "audits" .= Map.fromList [(stage, report) | (stage,_,report) <- stages],
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
     "claim" .= ("Native GHC and strict pre/post Core; JVM tests own independent model and compiled execution" :: String)]
  putStrLn ("small-arrays: " ++ show (length parsed) ++ " native rows, strict pre/post Core")
