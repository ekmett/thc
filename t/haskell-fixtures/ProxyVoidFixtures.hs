-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : ProxyVoidFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for proxy void.
module ProxyVoidFixtures (prepareProxyVoid) where

import Control.Monad (forM, unless, when)
import Data.Aeson (Value(..), eitherDecodeFileStrict, (.=), object)
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString as BS
import qualified Data.ByteString.Char8 as BSC
import Data.Int (Int64)
import Data.List (sort)
import FixtureSupport
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareProxyVoid :: FilePath -> IO ()
prepareProxyVoid root = do
  let directory = "build/proxy-void"
      output = root </> directory
      logs = directory </> "commands"
      source = "t/fixtures/compiler/ProxyVoidAudit.hs"
      driver = "t/fixtures/compiler/ProxyVoidAuditNative.hs"
      predicate = "t/fixtures/compiler/ProxyVoidPredicate.hs"
      native = directory </> "native"
      api = directory </> "api"
      manifest = output </> "manifest.json"
      entries = ["direct", "returned", "tupleCase", "effect"] :: [String]
      json path = either die pure =<< eitherDecodeFileStrict (root </> path)
  mapM_ (createDirectoryIfMissing True . (root </>)) [native, api]
  stale <- doesFileExist manifest
  when stale (removeFile manifest)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- runLogged 30 root logs "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Proxy# fixture requires GHC 9.14.1")
  apiBuild <- runLogged 180 root logs "predicate-build" [] ghc
    ["--make", "-O0", "-dynamic", "-package", "ghc", "-isrc/compiler", "-odir", api, "-hidir", api,
     predicate, "-o", api </> "predicate"]
  apiRun <- runLogged 30 root logs "predicate-run" [] (root </> api </> "predicate") []
  built <- runLogged 180 root logs "native-build" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-it/fixtures/compiler",
     "-odir", native, "-hidir", native, driver, "-o", native </> "oracle"]
  observed <- runLogged 30 root logs "native-run" [] (root </> native </> "oracle") []
  let parse row = case splitTab row of
        [name, x, y] -> (,,) name <$> readInteger x <*> readInteger y
        _ -> Nothing
      inputs = [minBound, -4097, -1, 0, 1, 4097, maxBound] :: [Int64]
      expected = [(name, toInteger x, toInteger (x + if name == "tupleCase" then 10 else 7)) |
        name <- entries, x <- if name == "effect" then [0, 1, 4097, maxBound] else inputs]
  unless (traverse parse (lines (BSC.unpack (commandStdout observed))) == Just expected)
    (die "Native Proxy# rows disagree with independent Int64 arithmetic")
  BS.writeFile (output </> "oracle.tsv") (commandStdout observed)
  retained <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        report = directory </> stage </> "audit.json"
    exported <- runLogged 300 root logs (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", output </> stage </> "ghc")]
      "bin/export-core.sh" ("-fplugin-opt=THC.Plugin:pretty-diagnostics" : ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
        map ("-fplugin-opt=THC.Plugin:closure=" ++) entries ++ [source])
    modules <- sort <$> listDirectory (root </> core)
    let paths = [core </> name | name <- modules, takeExtension name == ".cbd"]
        diagnostics = [core </> name | name <- modules, takeExtension name == ".json"]
    audited <- runLogged 60 root logs (stage ++ "-audit") [] "python3"
      (["bin/audit-core.py", "--output", report] ++ concatMap (\entry -> ["--entry", "main:ProxyVoidAudit." ++ entry]) entries ++ paths)
    result <- json report
    case result of
      Object fields | KeyMap.lookup "accepted" fields == Just (Bool True),
        KeyMap.lookup "issues" fields == Just (Array mempty),
        KeyMap.lookup "missingGlobals" fields == Just (Array mempty),
        KeyMap.lookup "runtimeExternals" fields == Just (Array mempty) -> pure ()
      _ -> die ("Proxy# strict audit rejected " ++ stage)
    pure (report : paths ++ diagnostics ++ commandArtifacts exported ++ commandArtifacts audited)
  plugins <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  inputHashes <- hashes root $ sort $ [source, driver, predicate, "thc.cabal",
    "t/haskell-fixtures/ProxyVoidFixtures.hs", "t/haskell-fixtures/Main.hs", "t/haskell-fixtures/FixtureSupport.hs",
    "bin/build-compiler.sh", "bin/export-core.sh", "bin/toolchain.sh", "bin/plugin.py", "bin/audit-core.py",
    "bin/core-capabilities.json", "src/main/resources/thc/scalar-primop-signatures.json"] ++
    ["src/compiler/THC" </> file | file <- plugins, takeExtension file == ".hs"] ++
    ["bin" </> file | file <- scripts, take 5 file == "core_" && takeExtension file == ".py"]
  artifactHashes <- hashes root $ [directory </> "oracle.tsv", native </> "oracle", api </> "predicate"] ++
    concat retained ++ concatMap commandArtifacts [version, apiBuild, apiRun, built, observed]
  writeJson manifest $ object ["schema" .= (1 :: Int), "nativeRows" .= length expected,
    "entries" .= entries, "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "Proxy#: 25 native/model rows, throwing-producer control, six wired-key controls, strict pre/post audits accepted"
