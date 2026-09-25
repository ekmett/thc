-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module OriginalPosixDupFixtures (prepareOriginalPosixDup) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, toJSON, (.=))
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import qualified Data.Map.Strict as Map
import FixtureSupport
import Foreign.C.Types (CInt, CLong)
import Foreign.Ptr (Ptr, nullPtr)
import Foreign.Storable (sizeOf)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import qualified System.Info as Host
import Text.Read (readMaybe)

directory, source, driver :: FilePath
directory = "build/original-posix-dup"
source = "compiler/test-fixtures/OriginalPosixDupAudit.hs"
driver = "compiler/test-fixtures/OriginalPosixDupAuditNative.hs"

requests :: [(String, String)]
requests = [("originalDup", scenario) | scenario <-
  ["shared", "close-source", "append", "lowest0", "lowest1", "lowest2", "invalid", "closed"]] ++
  [("originalDupErrno", scenario) | scenario <- ["invalid", "closed"]] ++
  [("originalDup2", scenario) | scenario <- ["replace", "self", "alias", "invalid", "closed", "bad-target"]] ++
  [("originalDup2Errno", scenario) | scenario <- ["invalid", "closed", "bad-target"]]

entries :: [String]
entries = ["originalDup", "originalDupErrno", "originalDup2", "originalDup2Errno"]

prepareOriginalPosixDup :: FilePath -> IO ()
prepareOriginalPosixDup root = do
  let output = root </> directory
      execute = runLogged 120 root (directory </> "logs")
      native = directory </> "native"
      binary = native </> "oracle"
      results = directory </> "results"
  createDirectoryIfMissing True (root </> native)
  createDirectoryIfMissing True (root </> results)
  stale <- doesFileExist (output </> "manifest.json")
  when stale (removeFile (output </> "manifest.json"))
  unless (Host.os `elem` ["linux", "darwin"] && sizeOf (0 :: CInt) == 4 &&
          sizeOf (0 :: CLong) == 8 && sizeOf (nullPtr :: Ptr ()) == 8) $
    die "Original dup preparation requires a Linux/macOS LP64 host"
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original dup requires pinned GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | Just host <- lookup "Host platform" target,
                  not (null host), lookup "Target platform" target == Just host,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original dup preparation rejects cross-compiling or non-64-bit GHC"
  compiled <- execute "native-build" [] ghc ["--make", "-j2", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-package", "ghc-internal", "-package", "unix", "-i" ++ (root </> "compiler/test-fixtures"),
    "-odir", root </> native, "-hidir", root </> native, driver, "-o", root </> binary]
  rows <- forM (zip [0 :: Int ..] requests) $ \(index, (entry, scenario)) -> do
    let number = show index
        privateFile = results </> (number ++ ".private")
        otherFile = results </> (number ++ ".other")
        resultFile = results </> (number ++ ".txt")
    observed <- runLogged 10 root (directory </> "logs") ("native-" ++ number) [] (root </> binary)
      [entry, scenario, root </> privateFile, root </> otherFile, root </> resultFile]
    text <- BSC.unpack <$> BSC.readFile (root </> resultFile)
    (value, fd, target, position, byte, errno, contents) <- maybe (die ("Malformed native dup result: " ++ resultFile)) pure
      (readMaybe text :: Maybe (Integer, Integer, Integer, Integer, Integer, Integer, [Integer]))
    let observation = object ["entry" .= entry, "scenario" .= scenario, "result" .= value,
          "source" .= fd, "target" .= target, "position" .= position, "byte" .= byte,
          "errno" .= errno, "contents" .= contents,
          "stdoutHex" .= hexBytes (commandStdout observed), "stderrHex" .= hexBytes (commandStderr observed)]
    pure (observation, observed, [resultFile, privateFile, otherFile])
  let oracle = directory </> "oracle.json"
  writeJson (root </> oracle) (toJSON [row | (row, _, _) <- rows])
  exports <- forM ["pre", "post"] $ \stage -> do
    let stageDir = directory </> stage
        core = stageDir </> "core"
        modules = [core </> "OriginalPosixDupAudit.json", core </> "THC.InterfaceClosure.json"]
        postTidy = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
        roots = ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> stageDir </> "ghc"), ("THC_SOURCE_NOTES", "true")]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ postTidy ++ roots ++ [source])
    audits <- forM entries $ \entry -> do
      let path = stageDir </> entry ++ ".audit.json"
      audited <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["scripts/audit-core.py", "--entry", entry, "--output", path] ++ modules)
      pure (entry, path, audited)
    pure (stage, object ["modules" .= modules], Map.fromList [(entry, path) | (entry, path, _) <- audits],
          exported : [command | (_, _, command) <- audits], modules ++ [path | (_, path, _) <- audits])
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let commands = [version, info, compiled] ++ [command | (_, command, _) <- rows] ++
        concat [stageCommands | (_, _, _, stageCommands, _) <- exports]
      sources = sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/OriginalPosixDupFixtures.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> path | path <- plugin, takeExtension path == ".hs"] ++
        ["scripts" </> path | path <- scripts, "core_" `isPrefixOf` path, takeExtension path == ".py"]
      artifacts = binary : oracle : concat [paths | (_, _, paths) <- rows] ++
        concat [paths | (_, _, _, _, paths) <- exports] ++ concatMap commandArtifacts commands
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson (output </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "ghcInfo" .= BSC.unpack (commandStdout info),
     "entries" .= entries, "nativeRows" .= length rows,
     "stages" .= Map.fromList [(stage, settings) | (stage, settings, _, _, _) <- exports],
     "audits" .= Map.fromList [(stage, audits) | (stage, _, audits, _, _) <- exports],
     "strictAccepted" .= True, "runtimeVerified" .= False, "commands" .= map commandRecord commands,
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "original-posix-dup: 19 native observations, 2 original Core stages"
