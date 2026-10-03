-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (076 original-stdio-truncate)
-- Purpose: Check truncation updates length and position through the guest I/O boundary.
-- Inputs: OriginalStdioTruncateAudit.hs/Native.hs, selected GHC/native libraries,
--   exporter and auditor. CMake owns every persistent product.
-- Produces: Native executable, fourteen result files, ten private file images,
--   oracle.json, pre/post CBD pairs, two batched audits, logs and manifest.
-- Cost and overlap: One native process. ManagedFiles tests cover provider behavior;
--   this checks the real c_ftruncate declaration and COff transport through export,
--   closure loading and first compiled calls, preserving file position on resize.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 076.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : OriginalStdioTruncateFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : POSIX; depends on the unix package
--
-- The installed GHC declaration supplies Core; a private native fd supplies
-- observations. Java owns the independent model and compiled comparisons.
module OriginalStdioTruncateFixtures (prepareOriginalStdioTruncate) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, toJSON, (.=))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport
import Foreign.C.Types (CInt, CLong)
import System.Posix.Types (COff)
import Foreign.Ptr (Ptr, nullPtr)
import Foreign.Storable (sizeOf)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import qualified System.Info as Host
import Text.Read (readMaybe)

directory, source, driver :: FilePath
directory = "build/original-stdio-truncate"
source = "t/fixtures/compiler/OriginalStdioTruncateAudit.hs"
driver = "t/fixtures/compiler/OriginalStdioTruncateAuditNative.hs"

entries :: [String]
entries = ["originalTruncate", "originalTruncateErrno"]

requests :: [(String, String)]
requests = [(entry, scenario) | entry <- entries, scenario <- scenarios]

scenarios, fileScenarios :: [String]
scenarios = ["shrink", "same", "extend", "negative", "readonly", "invalid", "pipe"]
fileScenarios = take 5 scenarios

lengthFor :: String -> Int
lengthFor scenario = case scenario of
  "shrink" -> 3
  "same" -> 6
  "extend" -> 9
  "negative" -> -1
  "readonly" -> 3
  _ -> 0

prepareOriginalStdioTruncate :: FilePath -> IO ()
prepareOriginalStdioTruncate root = do
  let output = root </> directory
      execute = runLogged 120 root (directory </> "logs")
      native = directory </> "native"
      binary = native </> "oracle"
      results = directory </> "results"
  createDirectoryIfMissing True (root </> native)
  createDirectoryIfMissing True (root </> results)
  unless (Host.os `elem` ["linux", "darwin"] && sizeOf (0 :: CInt) == 4 &&
          sizeOf (0 :: CLong) == 8 && sizeOf (0 :: COff) == 8 &&
          sizeOf (nullPtr :: Ptr ()) == 8) $
    die "Original truncate native preparation requires a Linux/macOS LP64 host"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original truncate requires pinned GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | Just host <- lookup "Host platform" target,
                  not (null host), lookup "Target platform" target == Just host,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original truncate preparation rejects cross-compiling or non-64-bit GHC"
  compiled <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-package", "ghc-internal", "-package", "unix", "-i" ++ (root </> "t/fixtures/compiler"),
    "-odir", root </> native, "-hidir", root </> native, driver, "-o", root </> binary]
  let cases = [(entry, scenario, results </> show index ++ ".private", results </> show index ++ ".txt")
        | (index, (entry, scenario)) <- zip [0 :: Int ..] requests]
  observed <- execute "native-observations" [] (root </> binary)
    (concat [[entry, scenario, root </> privateFile, root </> resultFile]
      | (entry, scenario, privateFile, resultFile) <- cases])
  rows <- forM cases $ \(entry, scenario, privateFile, resultFile) -> do
    text <- BSC.unpack <$> BS.readFile (root </> resultFile)
    (result, observedSize, observedPosition) <- case lines text of
      [status, size, position] | Just value <- readInteger status,
        Just sizeValue <- readInteger size, Just offset <- readInteger position ->
        pure (value, sizeValue, offset)
      _ -> die ("Malformed native truncate result: " ++ resultFile)
    unless (text == show result ++ "\n" ++ show observedSize ++ "\n" ++ show observedPosition ++ "\n")
      (die ("Malformed native truncate result framing: " ++ resultFile))
    when (scenario `elem` fileScenarios) $ do
      contents <- BS.readFile (root </> privateFile)
      let expected = case scenario of
            "shrink" -> "abc"
            "extend" -> "abcdef\0\0\0"
            _ -> "abcdef"
      unless (contents == expected && fromIntegral (BS.length contents) == observedSize && observedPosition == 4)
        (die "Original truncate changed unexpected private-file bytes")
    let observation = object ["entry" .= entry, "scenario" .= scenario,
          "length" .= lengthFor scenario, "size" .= observedSize, "position" .= observedPosition,
          "result" .= result]
    pure (observation, resultFile, [privateFile | scenario `elem` fileScenarios])
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON [row | (row, _, _) <- rows])
  exports <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "OriginalStdioTruncateAudit.cbd", core </> "THC.InterfaceClosure.cbd"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc"),
       ("THC_SOURCE_NOTES", "true")]
      "bin/export-core.sh" (["-package", "ghc-internal"] ++ postTidy ++ roots ++ [source])
    mapM_ (\path -> do
      exists <- doesFileExist (root </> path)
      unless exists (die ("Missing genuine GHC truncate export: " ++ path))) modules
    let path = stageDir </> "audit.json"
    audited <- execute (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", path] ++ modules ++
       concat [["--entry", "main:OriginalStdioTruncateAudit." ++ entry] | entry <- entries])
    pure (stage, object ["modules" .= modules], path, [exported, audited], modules ++ [path])
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let commands = [version, info, compiled, observed] ++
        concat [stageCommands | (_, _, _, stageCommands, _) <- exports]
      sources = sort $ [source, driver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/OriginalStdioTruncateFixtures.hs",
        "bin/audit-core.py", "bin/core_original_foreign.py", "bin/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json", "bin/build-compiler.sh", "bin/export-core.sh",
        "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> path | path <- plugin, takeExtension path == ".hs"] ++
        ["bin" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = binary : oracle : [path | (_, path, _) <- rows] ++
        concat [privateFiles | (_, _, privateFiles) <- rows] ++
        concat [paths | (_, _, _, _, paths) <- exports] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
     "ghcInfo" .= BSC.unpack (commandStdout info), "entries" .= entries,
     "nativeRows" .= length rows,
     "stages" .= Map.fromList [(stage, settings) | (stage, settings, _, _, _) <- exports],
     "audits" .= Map.fromList [(stage, audits) | (stage, _, audits, _, _) <- exports],
     "strictAccepted" .= True, "runtimeVerified" .= False,
     "commands" .= map commandRecord commands, "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes]
  putStrLn "original-stdio-truncate: 14 native observations, 2 Core stages"
