-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- The installed GHC declaration supplies Core; a private native fd supplies
-- observations. Kotlin owns the independent model and compiled comparisons.
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
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import qualified System.Info as Host
import Text.Read (readMaybe)

directory, source, driver :: FilePath
directory = "build/original-stdio-truncate"
source = "compiler/test-fixtures/OriginalStdioTruncateAudit.hs"
driver = "compiler/test-fixtures/OriginalStdioTruncateAuditNative.hs"

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
    "-package", "ghc-internal", "-package", "unix", "-i" ++ (root </> "compiler/test-fixtures"),
    "-odir", root </> native, "-hidir", root </> native, driver, "-o", root </> binary]
  rows <- forM (zip [0 :: Int ..] requests) $ \(index, (entry, scenario)) -> do
    let number = show index
        label = "native-" ++ number
        privateFile = results </> (number ++ ".private")
        resultFile = results </> (number ++ ".txt")
    stale <- doesFileExist (root </> resultFile)
    when stale (removeFile (root </> resultFile))
    observed <- runLogged 10 root (directory </> "logs") label [] (root </> binary)
      [entry, scenario, root </> privateFile, root </> resultFile]
    text <- BSC.unpack <$> BS.readFile (root </> resultFile)
    (result, observedSize) <- case lines text of
      [status, size] | Just value <- readInteger status, Just length <- readInteger size ->
        pure (value, length)
      _ -> die ("Malformed native truncate result: " ++ resultFile)
    unless (text == show result ++ "\n" ++ show observedSize ++ "\n")
      (die ("Malformed native truncate result framing: " ++ resultFile))
    when (scenario `elem` fileScenarios) $ do
      contents <- BS.readFile (root </> privateFile)
      let expected = case scenario of
            "shrink" -> "abc"
            "extend" -> "abcdef\0\0\0"
            _ -> "abcdef"
      unless (contents == expected && fromIntegral (BS.length contents) == observedSize)
        (die "Original truncate changed unexpected private-file bytes")
    let observation = object ["entry" .= entry, "scenario" .= scenario,
          "length" .= lengthFor scenario, "size" .= observedSize, "result" .= result,
          "stdoutHex" .= hexBytes (commandStdout observed), "stderrHex" .= hexBytes (commandStderr observed)]
    pure (observation, observed, resultFile, [privateFile | scenario `elem` fileScenarios])
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON [row | (row, _, _, _) <- rows])
  exports <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "OriginalStdioTruncateAudit.json", core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc"),
       ("THC_SOURCE_NOTES", "true")]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ postTidy ++ roots ++ [source])
    mapM_ (\path -> do
      exists <- doesFileExist (root </> path)
      unless exists (die ("Missing genuine GHC seek export: " ++ path))) modules
    audits <- forM entries $ \entry -> do
      let path = stageDir </> entry ++ ".audit.json"
      audited <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py", "--entry", entry, "--output", path] ++ modules)
      pure (entry, path, audited)
    pure (stage, object ["modules" .= modules], Map.fromList [(entry, path) | (entry, path, _) <- audits],
          exported : [command | (_, _, command) <- audits], modules ++ [path | (_, path, _) <- audits])
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let commands = [version, info, compiled] ++ [command | (_, command, _, _) <- rows] ++
        concat [stageCommands | (_, _, _, stageCommands, _) <- exports]
      sources = sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/OriginalStdioTruncateFixtures.hs",
        "scripts/audit-core.py", "scripts/core_original_foreign.py", "scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json", "compiler/build.sh", "compiler/export.sh",
        "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> path | path <- plugin, takeExtension path == ".hs"] ++
        ["scripts" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = binary : oracle : [path | (_, _, path, _) <- rows] ++
        concat [privateFiles | (_, _, _, privateFiles) <- rows] ++
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
