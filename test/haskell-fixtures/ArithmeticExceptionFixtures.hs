-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module ArithmeticExceptionFixtures (prepareArithmeticExceptions) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import Data.List (sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

directory, source, driver :: FilePath
directory = "build/arithmetic-exceptions"
source = "compiler/test-fixtures/ArithmeticExceptionsAudit.hs"
driver = "compiler/test-fixtures/ArithmeticExceptionsNative.hs"

entries :: [String]
entries = ["divideOrAdd", "underflowOrAdd", "overflowOrAdd",
           "catchDivide", "catchUnderflow", "catchOverflow"]

inputs :: [Int]
inputs = [-2, -1, 0, 1, 7]

prepareArithmeticExceptions :: FilePath -> IO ()
prepareArithmeticExceptions root = do
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Arithmetic exception fixture requires GHC 9.14.1")
  let native = directory </> "native"
      executable = root </> native </> "arithmetic-exceptions-oracle"
  createDirectoryIfMissing True (root </> native)
  _ <- run root [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "compiler/test-fixtures"), "-odir", root </> native,
    "-hidir", root </> native, driver, "-o", executable] ""
  let requests = concat [name ++ "\t" ++ show input ++ "\n" | name <- entries, input <- inputs]
  observations <- runWithTimeout (Just 30000000) root [] executable [] requests
  let expected = [(name, show input) | name <- entries, input <- inputs]
      actual = [(name, input) | line <- lines observations,
        [name, input, _] <- [splitTab line]]
  unless (actual == expected) (die "Native arithmetic exception oracle lost or reordered rows")
  writeFile (root </> directory </> "oracle.tsv") observations
  stages <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        moduleFile = core </> "ArithmeticExceptionsAudit.json"
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ name | name <- entries]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc")]
      "compiler/export.sh" (postTidy ++ roots ++ [source]) ""
    exists <- doesFileExist (root </> moduleFile)
    unless exists (die ("Missing genuine arithmetic exception Core export: " ++ moduleFile))
    exported <- sort . filter ((== ".json") . takeExtension) <$> listDirectory (root </> core)
    unless (exported == ["ArithmeticExceptionsAudit.json", "THC.InterfaceClosure.json"]) $
      die ("Unexpected arithmetic exception Core inventory: " ++ show exported)
    pure (stage, [moduleFile, core </> "THC.InterfaceClosure.json"])
  let inputsToHash = sort [source, driver, "test/haskell-fixtures/ArithmeticExceptionFixtures.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh"]
      artifacts = (directory </> "oracle.tsv") : concatMap snd stages
  inputHashes <- hashes root inputsToHash
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "inputs" .= inputs, "stages" .= Map.fromList stages,
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "arithmetic-exceptions: 30 typed native oracle rows and genuine pre/post Core exports"

splitTab :: String -> [String]
splitTab [] = [""]
splitTab ('\t':rest) = "" : splitTab rest
splitTab (char:rest) = case splitTab rest of
  [] -> [[char]]
  field:fields -> (char:field) : fields
