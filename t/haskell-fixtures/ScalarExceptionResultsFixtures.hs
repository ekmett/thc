-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ScalarExceptionResultsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Genuine scalar-result exception fixtures and independently retained oracle.
module ScalarExceptionResultsFixtures (prepareScalarExceptionResults) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareScalarExceptionResults :: FilePath -> IO ()
prepareScalarExceptionResults root = do
  let directory = "build/scalar-exception-results"
      source = "t/fixtures/compiler/ScalarExceptionResultsAudit.hs"
      driver = "t/fixtures/compiler/ScalarExceptionResultsNative.hs"
      entries = [prefix ++ suffix | prefix <- ["normal", "throw", "interrupt"], suffix <- ["Int", "Word", "Addr"]]
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
      native = directory </> "native"
      binary = native </> "oracle"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Scalar exception results require GHC 9.14.1")
  compiled <- run "native-compile" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-i./t/fixtures/compiler", "-odir", native, "-hidir", native, driver, "-o", binary]
  observed <- run "native-oracle" [] (root </> binary) []
  unless (length (BS.lines (commandStdout observed)) == 36) (die "Scalar exception oracle row count changed")
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    mapM_ (createDirectoryIfMissing True . (root </>)) [core, ghcOut]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "bin/export-core.sh" (options ++ [source])
    audits <- forM entries $ \entry -> run (stage ++ "-audit-" ++ entry) [] "python3"
      ["bin/audit-core.py", "--entry", "main:ScalarExceptionResultsAudit." ++ entry, "--output", directory </> stage </> entry ++ "-audit.json",
       core </> "ScalarExceptionResultsAudit.cbd"]
    pure (exported : audits)
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  let inputs = sort $ [source, driver, "t/haskell-fixtures/ScalarExceptionResultsFixtures.hs",
        "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
        "thc.cabal", "cabal.project", "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/export-core.sh", "bin/build-compiler.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [version, compiled, observed] ++ concat stages
      artifacts = [binary] ++ concatMap commandArtifacts commands ++
        [directory </> stage </> "core/ScalarExceptionResultsAudit.cbd" | stage <- ["pre", "post"]] ++
        [directory </> stage </> entry ++ "-audit.json" | stage <- ["pre", "post"], entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "oracle" .= (directory </> "logs/native-oracle.stdout"),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn "Prepared scalar exception results: 36 native rows, pre/post strict Core"
