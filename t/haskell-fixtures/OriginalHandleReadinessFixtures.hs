-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- Fixture rationale (100 original-handle-readiness)
-- Purpose: A native PTY stays live through a THC descriptor alias and first compiled
--   c_isatty call. Ordinary nonterminal/errno cases live in UnixLibcTest and
--   OriginalErrnoTest; no separate libc conformance oracle is generated here.
-- Consumes: the two named Haskell sources, selected GHC/unix/ghc-internal, exporter
--   and auditor. Produces native/oracle, two CBD pairs, two audits and command logs.
-- Cost: One native helper build and one export/audit per stage; the test starts
--   one PTY helper, keeping it alive across stages and backends.
-- Build status: CMake owns every named Linux x86_64 product, or an unsupported
--   manifest elsewhere. No installed-Core acquisition or empty-directory input.
-- Detailed file inputs/outputs: docs/fixture-inputs.log, entry 100.

{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : OriginalHandleReadinessFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for original handle readiness.
module OriginalHandleReadinessFixtures (prepareOriginalHandleReadiness) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BSC
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import System.Info (arch, os)
import Text.Read (readMaybe)

prepareOriginalHandleReadiness :: FilePath -> IO ()
prepareOriginalHandleReadiness root
  | os /= "linux" || arch /= "x86_64" = do
      let output = root </> "build/original-handle-readiness"
      createDirectoryIfMissing True output
      writeJson (output </> "manifest.json") $ object
        ["schema" .= (1 :: Int), "supported" .= False, "platform" .= os,
         "artifactHashes" .= object []]
  | otherwise = preparePty root

preparePty :: FilePath -> IO ()
preparePty root = do
  let directory = "build/original-handle-readiness"
      source = "t/fixtures/compiler/OriginalHandleReadinessAudit.hs"
      driver = "t/fixtures/compiler/OriginalHandleReadinessNative.hs"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> directory </> "native")
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Original Handle readiness requires pinned GHC 9.14.1")
  info <- execute "ghc-info" [] ghc ["--info"]
  case readMaybe (BSC.unpack (commandStdout info)) :: Maybe [(String,String)] of
    Just target | Just host <- lookup "Host platform" target,
                  not (null host), lookup "Target platform" target == Just host,
                  lookup "target word size" target == Just "8" -> pure ()
    _ -> die "Original Handle readiness requires a native 64-bit GHC"
  let binary = directory </> "native" </> "oracle"
  compiled <- execute "native-build" [] ghc ["--make", "-O2", "-fforce-recomp", "-dcore-lint",
    "-package", "ghc-internal", "-package", "unix", "-odir", root </> directory </> "native",
    "-hidir", root </> directory </> "native", driver, "-o", root </> binary]
  exports <- forM ["pre","post"] $ \stage -> do
    let core = directory </> stage </> "core"
        modules = [core </> "OriginalHandleReadinessAudit.cbd",core </> "THC.InterfaceClosure.cbd"]
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- ["originalIsTerminal"]]
    exported <- execute (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage </> "ghc")]
      "bin/export-core.sh" (["-package", "ghc-internal"] ++ options ++ [source])
    mapM_ (\path -> do
      present <- doesFileExist (root </> path)
      unless present (die ("Missing genuine GHC export: " ++ path))) modules
    audits <- forM ["originalIsTerminal"] $ \entry -> do
      let path = directory </> stage </> entry ++ ".audit.json"
      command <- execute (stage ++ "-audit-" ++ entry) [] "python3"
        (["bin/audit-core.py", "--entry", "main:OriginalHandleReadinessAudit." ++ entry, "--output", path] ++ modules)
      pure (path,command)
    pure (modules, exported, audits)
  let sources = [source,driver,"thc.cabal", "t/haskell-fixtures/Main.hs",
        "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/OriginalHandleReadinessFixtures.hs",
        "bin/audit-core.py", "bin/core_original_foreign.py", "bin/core-capabilities.json",
        "bin/export-core.sh", "bin/build-compiler.sh", "src/compiler/THC/Plugin.hs"]
      commands = [version,info,compiled] ++
        concat [exported : [command | (_,command) <- audits] | (_,exported,audits) <- exports]
      artifacts = [binary] ++ concat [modules ++ [path | (path,_) <- audits] | (modules,_,audits) <- exports] ++
        concatMap commandArtifacts commands
  inputHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "supported" .= True, "platform" .= os,
     "stages" .= (["pre", "post"] :: [String]), "inputHashes" .= inputHashes,
     "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "original-handle-readiness: live PTY helper and pre/post Core prepared"
