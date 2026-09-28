-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : WideCharAddressFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for wide char address.
module WideCharAddressFixtures (prepareWideCharAddress) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import Text.Read (readMaybe)

prepareWideCharAddress :: FilePath -> IO ()
prepareWideCharAddress root = do
  let directory = "build/wide-char-address"
      source = "test/fixtures/compiler/WideCharAddressAudit.hs"
      driver = "test/fixtures/compiler/WideCharAddressNative.hs"
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
  createDirectoryIfMissing True (root </> directory)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Wide Char fixtures require GHC 9.14.1")
  info <- run "ghc-info" [] ghc ["--info"]
  settings <- maybe (die "Malformed GHC platform information") pure
    (readMaybe (BS.unpack (commandStdout info)) :: Maybe [(String,String)])
  unless (lookup "target word size" settings == Just "8" &&
    lookup "Host platform" settings /= Nothing &&
    lookup "Host platform" settings == lookup "Target platform" settings)
    (die "Wide Char fixtures require a native 64-bit GHC target")
  let native = directory </> "native"
      binary = native </> "oracle"
  createDirectoryIfMissing True (root </> native)
  compiled <- run "native-compile" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-i./test/fixtures/compiler", "-odir", native, "-hidir", native, driver, "-o", binary]
  observed <- run "native-oracle" [] (root </> binary) []
  unless (length (BS.lines (commandStdout observed)) == 15) (die "Wide Char oracle row count changed")
  stages <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    createDirectoryIfMissing True (root </> core)
    createDirectoryIfMissing True (root </> ghcOut)
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "bin/export-core.sh" (options ++ ["-fplugin-opt=THC.Plugin:closure=wideCharRoundtrip", source])
    audited <- run (stage ++ "-audit") [] "python3"
      ["bin/audit-core.py", "--entry", "wideCharRoundtrip", "--output", directory </> stage </> "audit.json",
       core </> "WideCharAddressAudit.json", core </> "THC.InterfaceClosure.json"]
    pure (stage, exported, audited)
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let sources = sort $ [source, driver, "test/haskell-fixtures/WideCharAddressFixtures.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "thc.cabal", "cabal.project", "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/export-core.sh", "bin/build-compiler.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [version, info, compiled, observed] ++ concat [[exported,audited] | (_,exported,audited) <- stages]
      artifacts = [binary] ++ concatMap commandArtifacts commands ++
        [directory </> stage </> file | stage <- ["pre","post"],
          file <- ["audit.json", "core/WideCharAddressAudit.json", "core/THC.InterfaceClosure.json"]]
  inputs <- hashes root sources
  outputs <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
     "entries" .= (["wideCharRoundtrip"] :: [String]),
     "oracle" .= (directory </> "logs/native-oracle.stdout"),
     "inputHashes" .= inputs, "artifactHashes" .= outputs,
     "commands" .= map commandRecord commands,
     "installedArtifactsHashed" .= False, "runtimeVerified" .= False]
  putStrLn "Prepared Wide Char Addr: 15 native rows, pre/post strict Core"
