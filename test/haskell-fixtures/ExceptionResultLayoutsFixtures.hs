-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ExceptionResultLayoutsFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Genuine concrete-layout exception fixtures and independently retained oracle.
module ExceptionResultLayoutsFixtures (prepareExceptionResultLayouts) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import System.Info (arch)

prepareExceptionResultLayouts :: FilePath -> IO ()
prepareExceptionResultLayouts root = do
  let directory = "build/exception-result-layouts"
      source = "compiler/test-fixtures/ExceptionResultLayoutsAudit.hs"
      driver = "compiler/test-fixtures/ExceptionResultLayoutsNative.hs"
      entries = [name ++ "Result" | name <- ["int8", "word8", "int16", "word16", "int32", "word32",
        "int64", "word64", "float", "double", "empty", "nested", "sum", "vector", "unlifted", "unliftedPayload"]]
      modes entry = if entry == "unliftedPayloadResult" then [0, 1 :: Int] else [0, 1, 2]
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
      native = directory </> "native"
      binary = native </> "oracle"
      stagesToExport = if arch == "aarch64" then ["pre"] else ["pre", "post"]
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Exception result layouts require GHC 9.14.1")
  compiled <- run "native-compile" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-i./compiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", binary]
  observed <- fmap concat $ forM entries $ \entry -> forM (modes entry) $ \mode -> do
    result <- run ("native-" ++ entry ++ "-" ++ show mode) [] (root </> binary) [entry, show mode]
    unless (length (BS.lines (commandStdout result)) == 3) (die "Exception layout oracle row count changed")
    pure result
  BS.writeFile (root </> directory </> "oracle.tsv") (BS.concat (map commandStdout observed))
  -- As in the SIMD fixtures, AArch64 NCG cannot emit vector instructions.
  -- The model above needs no SIMD codegen. Its genuine Core is pre-Tidy only
  -- there; -fno-code does not execute GHC's post-Tidy latePlugin hook.
  stages <- forM stagesToExport $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          (if arch == "aarch64" then ["-fno-code", "-fwrite-if-simplified-core"] else [])
    mapM_ (createDirectoryIfMissing True . (root </>)) [core, ghcOut]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "compiler/export.sh" (options ++ [source])
    audits <- forM entries $ \entry -> run (stage ++ "-audit-" ++ entry) [] "python3"
      ["scripts/audit-core.py", "--entry", entry, "--output", directory </> stage </> entry ++ "-audit.json",
       core </> "ExceptionResultLayoutsAudit.json"]
    pure (exported : audits)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/ExceptionResultLayoutsFixtures.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "thc.cabal", "cabal.project", "scripts/audit-core.py", "scripts/core-capabilities.json",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [version, compiled] ++ observed ++ concat stages
      artifacts = [binary, directory </> "oracle.tsv"] ++ concatMap commandArtifacts commands ++
        [directory </> stage </> "core/ExceptionResultLayoutsAudit.json" | stage <- stagesToExport] ++
        [directory </> stage </> entry ++ "-audit.json" | stage <- stagesToExport, entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "stages" .= stagesToExport, "postTidyAvailable" .= (arch /= "aarch64"),
     "oracle" .= (directory </> "oracle.tsv"),
     "oracleKind" .= ("native-boxed-effects-independent-layout-model" :: String),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn ("Prepared exception result layouts: 141 native boxed-effect/model rows, strict Core stages " ++ show stagesToExport)
