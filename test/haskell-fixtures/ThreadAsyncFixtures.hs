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
      lazySource = "compiler/test-fixtures/LazyForkAudit.hs"
      lazyDriver = "compiler/test-fixtures/LazyForkNative.hs"
      entries = ["forkAndThrow", "killUncaught", "selfThrow", "maskedUnmaskSelf"]
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (lines version == ["9.14.1"]) (die "Public thread fixture requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core),
      ("THC_GHC_OUT", output </> stage </> "ghc")]
      "compiler/export.sh" (options ++ [source]) ""
    forM_ entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      (status, _, errors) <- readCreateProcessWithExitCode
        ((proc "python3" ["scripts/audit-core.py", "--entry", entry,
          "--output", report, core </> "ThreadAsyncAudit.json"]) { cwd = Just root }) ""
      unless (status == ExitSuccess)
        (die ("Public thread Core audit failed: " ++ errors))
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just value | supportedThreadContract entry value -> pure ()
        _ -> die ("Public thread Core audit did not accept " ++ entry)
    _ <- run root [("THC_CORE_OUT", root </> core),
      ("THC_GHC_OUT", output </> stage </> "lazy-ghc")]
      "compiler/export.sh" (options ++ [lazySource]) ""
    let lazyReport = directory </> stage </> "lazyFork-audit.json"
    (lazyStatus, _, lazyErrors) <- readCreateProcessWithExitCode
      ((proc "python3" ["scripts/audit-core.py", "--entry", "lazyFork",
        "--output", lazyReport, core </> "LazyForkAudit.json"]) { cwd = Just root }) ""
    unless (lazyStatus == ExitSuccess)
      (die ("Lazy fork Core audit failed: " ++ lazyErrors))
    lazyBytes <- BS.readFile (root </> lazyReport)
    case decodeStrict' lazyBytes of
      Just value | supportedThreadContract "lazyFork" value -> pure ()
      _ -> die "Lazy fork Core audit did not accept"
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures", "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["+RTS", "-N2", "-RTS"] ""
  unless (actual == "43\n44\n") (die "Public thread native fork/kill/resume oracle disagreed")
  writeFile (output </> "oracle.txt") actual
  extras <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["extras", "+RTS", "-N2", "-RTS"] ""
  unless (extras == "5\n-1\n-1\n") (die "Public thread uncaught/self delivery oracle disagreed")
  writeFile (output </> "extra-oracle.txt") extras
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "compiler/test-fixtures", "-odir", native, "-hidir", native,
    root </> lazyDriver, "-o", native </> "lazy-oracle"] ""
  lazyActual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "lazy-oracle")
    ["+RTS", "-N2", "-RTS"] ""
  unless (lazyActual == "52\n53\n") (die "Lazy fork native child ownership/resume oracle disagreed")
  writeFile (output </> "lazy-oracle.txt") lazyActual
  pluginFiles <- listDirectory (root </> "compiler/THC")
  coreScripts <- listDirectory (root </> "scripts")
  let sources = sort $ [source, driver, lazySource, lazyDriver, "thc.cabal", "test/haskell-fixtures/Main.hs",
        "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/ThreadAsyncFixtures.hs",
        "scripts/audit-core.py", "scripts/core-capabilities.json",
        "compiler/build.sh", "compiler/export.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt", directory </> "extra-oracle.txt", directory </> "lazy-oracle.txt"] ++
        [directory </> stage </> suffix | stage <- stages,
          suffix <- ["core/ThreadAsyncAudit.json", "core/LazyForkAudit.json", "lazyFork-audit.json"] ++
                    [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entry" .= ("forkAndThrow" :: String), "entries" .= entries, "stages" .= stages,
    "native" .= ([43, 44] :: [Int]), "extraNative" .= ([5, -1, -1] :: [Int]),
    "lazyNative" .= ([52, 53] :: [Int]),
    "inputHashes" .= sourceHashes,
    "artifactHashes" .= artifactHashes, "installedArtifactsHashed" .= False]
  putStrLn "thread-async: native fork#/myThreadId#/killThread#, strict pre/post Core"

supportedThreadContract :: String -> Value -> Bool
supportedThreadContract entry (Object report) = hasPublicThreadPrimitives && case KeyMap.lookup "accepted" report of
  Just (Bool True) ->
    KeyMap.lookup "missingGlobals" report == Just (Array mempty) &&
    KeyMap.lookup "issues" report == Just (Array mempty)
  _ -> False
  where
    hasPublicThreadPrimitives = case KeyMap.lookup "primitives" report of
      Just (Array primitives) ->
        let names = map primitiveName (toList primitives)
            required = case entry of
              "forkAndThrow" -> ["fork#", "myThreadId#", "killThread#", "catch#"]
              "killUncaught" -> ["fork#", "myThreadId#", "killThread#"]
              "selfThrow" -> ["myThreadId#", "killThread#", "catch#"]
              "maskedUnmaskSelf" -> ["myThreadId#", "killThread#", "catch#",
                "maskUninterruptible#", "unmaskAsyncExceptions#", "getMaskingState#"]
              "lazyFork" -> ["fork#", "killThread#"]
              _ -> []
        in not (null required) && all ((`elem` names) . Just . String) required &&
           Just "noDuplicate#" `notElem` names
      _ -> False
    primitiveName (Object primitive) = KeyMap.lookup "name" primitive
    primitiveName _ = Nothing
supportedThreadContract _ _ = False
