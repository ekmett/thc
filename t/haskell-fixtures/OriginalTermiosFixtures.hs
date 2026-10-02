-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : OriginalTermiosFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for original saved termios pointers.
module OriginalTermiosFixtures (prepareOriginalTermios) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BSC
import Data.List (isPrefixOf, sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import System.Info (os, arch)
import Text.Read (readMaybe)

prepareOriginalTermios :: FilePath -> IO ()
prepareOriginalTermios root
  | os /= "linux" || arch /= "x86_64" = do
      createDirectoryIfMissing True (root </> directory)
      inputHashes <- fixtureSources root >>= hashes root
      writeJson (root </> directory </> "manifest.json") $ object
        ["schema" .= (1 :: Int), "platform" .= os, "supported" .= False,
         "reason" .= ("Original Linux x86_64 saved-termios declarations only" :: String),
         "inputHashes" .= inputHashes, "artifactHashes" .= object []]
      putStrLn "original-termios: explicitly excluded on this platform"
  | otherwise = prepareLinux root

directory :: FilePath
directory = "build/original-termios"

fixtureSources :: FilePath -> IO [FilePath]
fixtureSources root = do
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  pure $ sort $ ["t/fixtures/compiler/OriginalSavedTermiosAudit.hs", "t/fixtures/compiler/OriginalSavedTermiosNative.hs",
    "thc.cabal", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "t/haskell-fixtures/OriginalTermiosFixtures.hs", "bin/audit-core.py", "bin/core-capabilities.json",
    "src/main/resources/thc/scalar-primop-signatures.json", "bin/export-core.sh", "bin/build-compiler.sh",
    "bin/toolchain.sh", "bin/plugin.py"] ++
    ["src/compiler/THC" </> name | name <- plugin, takeExtension name == ".hs"] ++
    ["bin" </> name | name <- scripts, "core_" `isPrefixOf` name, takeExtension name == ".py"]

prepareLinux :: FilePath -> IO ()
prepareLinux root = do
  let execute = runLogged 180 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory)
  let manifest = root </> directory </> "manifest.json"
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original termios requires GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | lookup "Host platform" target == Just "x86_64-unknown-linux",
                  lookup "Target platform" target == lookup "Host platform" target,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original termios requires native Linux x86_64 GHC"
  (savedCommands, savedArtifacts) <- prepareSavedTermios root ghc
  let commands = [version,info] ++ savedCommands
      artifacts = savedArtifacts ++ concatMap commandArtifacts commands
  inputHashes <- fixtureSources root >>= hashes root
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "platform" .= os, "supported" .= True,
     "entries" .= (["originalGetSavedTermios", "originalSetSavedTermios"] :: [String]), "strictAccepted" .= True,
     "runtimeVerified" .= False, "installedArtifactsHashed" .= False, "nativeRows" .= (28 :: Int), "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-termios: 28 native saved-pointer rows and strict pre/post originals prepared"

-- Keep this oracle separate from the termios image layout: it only observes
-- pointer retention, and its native bracket restores the preexisting RTS roots.
prepareSavedTermios :: FilePath -> FilePath -> IO ([CommandResult], [FilePath])
prepareSavedTermios root ghc = do
  let saved = directory </> "saved"
      entries = ["originalGetSavedTermios", "originalSetSavedTermios"]
      execute = runLogged 180 root (directory </> "logs")
      binary = saved </> "native/oracle"
      oracle = saved </> "oracle.json"
  createDirectoryIfMissing True (root </> saved </> "native")
  compiled <- execute "saved-native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint",
    "-package", "ghc-internal", "-odir", root </> saved </> "native", "-hidir", root </> saved </> "native",
    "t/fixtures/compiler/OriginalSavedTermiosNative.hs", "-o", root </> binary]
  observed <- execute "saved-native-run" [] (root </> binary) []
  rows <- maybe (die "Malformed original saved-termios observations") pure
    (readMaybe (BSC.unpack (commandStdout observed)) :: Maybe [[Integer]])
  unless (length rows == 28 && all ((== 6) . length) rows) (die "Incomplete original saved-termios oracle")
  writeJson (root </> oracle) $ object ["rows" .= rows]
  exports <- forM ["pre", "post"] $ \stage -> do
    let core = saved </> stage </> "core"
        modules = [core </> "OriginalSavedTermiosAudit.cbd", core </> "THC.InterfaceClosure.cbd"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
    exported <- execute ("saved-" ++ stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> saved </> stage </> "ghc")]
      "bin/export-core.sh" (["-package", "ghc-internal"] ++ options ++ ["t/fixtures/compiler/OriginalSavedTermiosAudit.hs"])
    audits <- forM entries $ \entry -> do
      let path = saved </> stage </> entry ++ ".audit.json"
      audited <- execute ("saved-" ++ stage ++ "-audit-" ++ entry) [] "python3"
        (["bin/audit-core.py", "--entry", "main:OriginalSavedTermiosAudit." ++ entry, "--output", path] ++ modules)
      pure (path, audited)
    pure (exported : map snd audits, modules ++ map fst audits)
  pure ([compiled, observed] ++ concatMap fst exports, [binary, oracle] ++ concatMap snd exports)
