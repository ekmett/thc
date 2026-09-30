-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ThreadInventoryFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for thread inventory.
module ThreadInventoryFixtures (prepareThreadInventory) where

import Control.Monad (forM_, unless, when)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import Data.List (sort)
import FixtureSupport (hashes, run, runWithTimeout, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import THC.Compact.Module (encodeModuleValue)

prepareThreadInventory :: FilePath -> IO ()
prepareThreadInventory root = do
  let directory = "build/thread-inventory"
      output = root </> directory
      manifest = output </> "manifest.json"
      source = "t/fixtures/core/ThreadInventory.hs"
      driver = "t/fixtures/compiler/ThreadInventoryNative.hs"
      callbackDriver = "t/fixtures/compiler/CallbackIdentityNative.hs"
      callbackC = "t/fixtures/compiler/callback-identity.c"
      entries = ["selfInventory", "boundQuery", "snapshotSize", "forkSnapshot", "lazyFork", "forkMasks", "selfKilledStatus", "parkedFork", "callbackObservation"]
      stages = ["pre", "post"]
  createDirectoryIfMissing True output
  present <- doesFileExist manifest
  when present (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run root [] ghc ["--numeric-version"] ""
  unless (version == "9.14.1\n") (die "Thread inventory requires GHC 9.14.1")
  forM_ stages $ \stage -> do
    let core = directory </> stage </> "core"
        options = ["-fplugin-opt=THC.Plugin:pretty-diagnostics"] ++
          ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    _ <- run root [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" (options ++ [source]) ""
    -- The printed types/names prove source call counts; execution uses the
    -- sibling CBD. Require both views to encode the exact same module.
    diagnostic <- BS.readFile (root </> core </> "ThreadInventory.json")
    diagnosticValue <- maybe (die "Invalid thread inventory diagnostic Core") pure (decodeStrict' diagnostic)
    encoded <- encodeModuleValue diagnosticValue
    compact <- BS.readFile (root </> core </> "ThreadInventory.cbd")
    unless (encoded == compact) (die "Thread inventory diagnostic/CBD export mismatch")
    forM_ entries $ \entry -> do
      let report = directory </> stage </> (entry ++ "-audit.json")
      _ <- run root [] "python3" ["bin/audit-core.py", "--entry", "main:ThreadInventory." ++ entry,
        "--output", report, core </> "ThreadInventory.cbd"] ""
      bytes <- BS.readFile (root </> report)
      case decodeStrict' bytes of
        Just (Object value) | KeyMap.lookup "accepted" value == Just (Bool True),
          KeyMap.lookup "issues" value == Just (Array mempty),
          KeyMap.lookup "missingGlobals" value == Just (Array mempty) -> pure ()
        _ -> die ("Strict thread inventory audit rejected " ++ entry)
  let native = output </> "native"
  createDirectoryIfMissing True native
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
    "-i" ++ (root </> "t/fixtures/core"), "-odir", native, "-hidir", native,
    root </> driver, "-o", native </> "oracle"] ""
  observations <- runWithTimeout (Just 30000000) root [] (native </> "oracle") ["+RTS", "-N2", "-RTS"] ""
  unless (observations == "10\n0\n111\n1\n42\n210\n17\n1\n") (die "Native thread inventory disagreed")
  writeFile (output </> "oracle.txt") observations
  _ <- run root [] ghc ["--make", "-O2", "-dynamic", "-threaded", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-i" ++ (root </> "t/fixtures/core"), "-odir", native, "-hidir", native,
    "-stubdir", native, root </> callbackDriver, root </> callbackC, "-o", native </> "callback-oracle"] ""
  callbacks <- runWithTimeout (Just 30000000) root [] (native </> "callback-oracle") ["+RTS", "-N2", "-RTS"] ""
  let expectedCallbacks = concatMap (\mask -> "(" ++ mask ++
        ",True,True,Unmasked,True,True,True,Unmasked,True,1,8)\n(True,True)\n")
        ["Unmasked", "MaskedInterruptible", "MaskedUninterruptible"]
  unless (callbacks == expectedCallbacks ++ "(True,True,True,True)\n") (die "Native callback identity/mask contract disagreed")
  writeFile (output </> "callback-oracle.txt") callbacks
  pluginFiles <- listDirectory (root </> "src/compiler/THC")
  coreScripts <- listDirectory (root </> "bin")
  let sources = sort $ [source, driver, callbackDriver, callbackC, "thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/ThreadInventoryFixtures.hs",
        "bin/audit-core.py", "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json",
        "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- pluginFiles, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- coreScripts, take 5 file == "core_" && takeExtension file == ".py"]
      artifacts = [directory </> "oracle.txt", directory </> "callback-oracle.txt"] ++
        [directory </> stage </> suffix | stage <- stages,
          suffix <- ["core/ThreadInventory.cbd", "core/ThreadInventory.json"] ++ [entry ++ "-audit.json" | entry <- entries]]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "entries" .= entries, "stages" .= stages, "native" .= ([10, 0, 111, 1, 42, 210, 17, 1] :: [Int]),
    "nativeThread" .= ("unbound forkIO, threaded RTS -N2" :: String),
    "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes]
  putStrLn "thread-inventory: native unbound/self/live-child snapshots, strict pre/post Core"
