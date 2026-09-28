-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ThreadAsyncFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for thread async.
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
      source = "t/fixtures/compiler/ThreadAsyncAudit.hs"
      driver = "t/fixtures/compiler/ThreadAsyncNative.hs"
      lazySource = "t/fixtures/compiler/LazyForkAudit.hs"
      lazyDriver = "t/fixtures/compiler/LazyForkNative.hs"
      entries = ["forkAndThrow", "killUncaught", "selfThrow", "maskedUnmaskSelf",
        "promptSelfThrow", "promptMaskedUnmaskSelf", "savedSelfThrow", "savedMaskedSelf",
        "savedSuffixSelf", "savedMaskCatchSelf", "externalSaved", "scheduledSaved", "yieldProbe", "yieldMasked"]
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
      "bin/export-core.sh" (options ++ [source]) ""
    forM_ entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      (status, _, errors) <- readCreateProcessWithExitCode
        ((proc "python3" ["bin/audit-core.py", "--entry", entry,
          "--output", report, core </> "ThreadAsyncAudit.json"]) { cwd = Just root }) ""
      unless (status == ExitSuccess)
        (die ("Public thread Core audit failed: " ++ errors))
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just value | supportedThreadContract entry value -> pure ()
        _ -> die ("Public thread Core audit did not accept " ++ entry)
    _ <- run root [("THC_CORE_OUT", root </> core),
      ("THC_GHC_OUT", output </> stage </> "lazy-ghc")]
      "bin/export-core.sh" (options ++ [lazySource]) ""
    let lazyReport = directory </> stage </> "lazyFork-audit.json"
    (lazyStatus, _, lazyErrors) <- readCreateProcessWithExitCode
      ((proc "python3" ["bin/audit-core.py", "--entry", "lazyFork",
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
    "-i" ++ root </> "t/fixtures/compiler", "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  actual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["+RTS", "-N2", "-RTS"] ""
  unless (actual == "43\n44\n") (die "Public thread native fork/kill/resume oracle disagreed")
  writeFile (output </> "oracle.txt") actual
  extras <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["extras", "+RTS", "-N2", "-RTS"] ""
  unless (extras == "5\n-1\n-1\n-1\n-1\n") (die "Public thread uncaught/self delivery oracle disagreed")
  writeFile (output </> "extra-oracle.txt") extras
  saved <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["saved", "+RTS", "-N2", "-RTS"] ""
  let savedNative = [220102, 220103, 220102, 220103, 220102, 220103, 110102, 110103] :: [Int]
  unless (saved == unlines (map show savedNative)) (die "Saved self-delivery native oracle disagreed")
  writeFile (output </> "saved-oracle.txt") saved
  external <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["external-saved", "+RTS", "-N2", "-RTS"] ""
  let externalNative = [22122122, 22122123] :: [Int]
  unless (external == unlines (map show externalNative)) (die "Saved external-delivery native oracle disagreed")
  writeFile (output </> "external-saved-oracle.txt") external
  scheduled <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["scheduled-saved", "+RTS", "-N2", "-RTS"] ""
  let scheduledNative = [96202106490, 97204107494] :: [Integer]
  unless (scheduled == unlines (map show scheduledNative)) (die "Saved scheduling native oracle disagreed")
  writeFile (output </> "scheduled-saved-oracle.txt") scheduled
  yielded <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "oracle")
    ["yield", "+RTS", "-N2", "-RTS"] ""
  unless (yielded == "37\n39\n") (die "Public thread yield# State/mask oracle disagreed")
  writeFile (output </> "yield-oracle.txt") yielded
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-dcore-lint", "-dstg-lint",
    "-i" ++ root </> "t/fixtures/compiler", "-odir", native, "-hidir", native,
    root </> lazyDriver, "-o", native </> "lazy-oracle"] ""
  lazyActual <- runWithTimeout (Just (30 * 1000000)) root [] (native </> "lazy-oracle")
    ["+RTS", "-N2", "-RTS"] ""
  unless (lazyActual == "52\n53\n") (die "Lazy fork native child ownership/resume oracle disagreed")
  writeFile (output </> "lazy-oracle.txt") lazyActual
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source, driver, lazySource, lazyDriver, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/ThreadAsyncFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt", directory </> "extra-oracle.txt",
        directory </> "yield-oracle.txt", directory </> "lazy-oracle.txt", directory </> "saved-oracle.txt",
        directory </> "external-saved-oracle.txt", directory </> "scheduled-saved-oracle.txt"] ++
        [directory </> stage </> suffix | stage <- stages,
          suffix <- ["core/ThreadAsyncAudit.json", "core/LazyForkAudit.json", "lazyFork-audit.json"] ++
                    [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entry" .= ("forkAndThrow" :: String), "entries" .= entries, "stages" .= stages,
    "native" .= ([43, 44] :: [Int]), "extraNative" .= ([5, -1, -1, -1, -1] :: [Int]),
    "yieldNative" .= ([37, 39] :: [Int]),
    "savedNative" .= savedNative,
    "externalSavedNative" .= externalNative,
    "scheduledSavedNative" .= scheduledNative,
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
              "promptSelfThrow" -> ["prompt#", "myThreadId#", "killThread#", "catch#"]
              "promptMaskedUnmaskSelf" -> ["prompt#", "myThreadId#", "killThread#", "catch#",
                "maskUninterruptible#", "unmaskAsyncExceptions#", "getMaskingState#"]
              name | name `elem` ["savedSelfThrow", "savedMaskedSelf", "savedSuffixSelf", "savedMaskCatchSelf"] ->
                ["prompt#", "control0#", "myThreadId#", "killThread#", "catch#", "getMaskingState#",
                 "newMutVar#", "readMutVar#", "writeMutVar#", "maskUninterruptible#", "unmaskAsyncExceptions#"]
              "lazyFork" -> ["fork#", "killThread#"]
              "externalSaved" -> ["fork#", "myThreadId#", "killThread#", "catch#", "prompt#", "control0#",
                "takeMVar#", "putMVar#", "readMutVar#", "writeMutVar#", "getMaskingState#"]
              "scheduledSaved" -> ["prompt#", "control0#", "maskUninterruptible#",
                "readMutVar#", "writeMutVar#", "getMaskingState#"]
              "yieldProbe" -> ["yield#", "getMaskingState#"]
              "yieldMasked" -> ["yield#", "maskUninterruptible#", "getMaskingState#"]
              _ -> []
        in not (null required) && all ((`elem` names) . Just . String) required &&
           Just "noDuplicate#" `notElem` names
      _ -> False
    primitiveName (Object primitive) = KeyMap.lookup "name" primitive
    primitiveName _ = Nothing
supportedThreadContract _ _ = False
