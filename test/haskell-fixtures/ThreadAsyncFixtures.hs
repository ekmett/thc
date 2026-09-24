-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}
module ThreadAsyncFixtures (prepareThreadAsync) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.Foldable (toList)
import Data.List (sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (ExitCode(..), die)
import System.FilePath ((</>), takeExtension)
import System.Process (CreateProcess(cwd), proc, readCreateProcessWithExitCode)

prepareThreadAsync :: FilePath -> IO ()
prepareThreadAsync root = do
  let directory = "build/thread-async"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "compiler/test-fixtures/ThreadAsyncAudit.hs"
      driver = "compiler/test-fixtures/ThreadAsyncNative.hs"
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Public thread fixture requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        report = directory </> stage </> "forkAndThrow-audit.json"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core),
      ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source]) ""
    (status, _, errors) <- readCreateProcessWithExitCode
      ((proc "python3" ["scripts/audit-core.py", "--entry", "forkAndThrow",
        "--output", report, core </> "ThreadAsyncAudit.json"]) { cwd = Just root }) ""
    unless (status == ExitSuccess)
      (die ("Public thread Core audit failed: " ++ errors))
    bytes <- BS.readFile (root </> report)
    case decodeStrict' bytes of
      Just value | supportedThreadContract value -> pure ()
      _ -> die "Public thread Core audit did not accept the exact threading contract"
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures", "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["+RTS", "-N2", "-RTS"] ""
  unless (actual == "43\n44\n") (die "Public thread native fork/kill/resume oracle disagreed")
  writeFile (output </> "oracle.txt") actual
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source, driver, "thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/ThreadAsyncFixtures.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = (directory </> "oracle.txt") : [directory </> stage </> suffix |
        stage <- stages, suffix <- ["core/ThreadAsyncAudit.json", "forkAndThrow-audit.json"]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entry" .= ("forkAndThrow" :: String), "stages" .= stages,
    "native" .= ([43, 44] :: [Int]), "inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes, "installedArtifactsHashed" .= False]
  putStrLn "thread-async: native fork#/myThreadId#/killThread#, strict pre/post Core"

supportedThreadContract :: Value -> Bool
supportedThreadContract (Object report) = hasPublicThreadPrimitives && case KeyMap.lookup "accepted" report of
  Just (Bool True) ->
    KeyMap.lookup "missingGlobals" report == Just (Array mempty) &&
    KeyMap.lookup "issues" report == Just (Array mempty)
  _ -> False
  where
    hasPublicThreadPrimitives = case KeyMap.lookup "primitives" report of
      Just (Array primitives) ->
        let names = map primitiveName (toList primitives)
        in all (`elem` names) [Just "fork#", Just "myThreadId#", Just "killThread#", Just "catch#"] &&
           Just "noDuplicate#" `notElem` names
      _ -> False
    primitiveName (Object primitive) = KeyMap.lookup "name" primitive
    primitiveName _ = Nothing
supportedThreadContract _ = False
