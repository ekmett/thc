-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : DeepEvaluationFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Native GHC; host filesystem/process services
--
-- Fixture acquisition support for deep evaluation.
module DeepEvaluationFixtures (prepareDeepEvaluation) where

import Control.Monad (forM, unless)
import Data.Aeson (Value(..), eitherDecodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import qualified Data.Text as Text
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), isRelative, makeRelative, takeExtension)

prepareDeepEvaluation :: FilePath -> IO ()
prepareDeepEvaluation root = do
  let directory = "build/deep-evaluation"
      source = "test/fixtures/compiler/DeepEvaluation.hs"
      driver = "test/fixtures/compiler/DeepEvaluationNative.hs"
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
      native = directory </> "native"
      binary = native </> "oracle"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Deep evaluation requires GHC 9.14.1")
  compiled <- run "native-compile" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-i./test/fixtures/compiler", "-odir", native, "-hidir", native, driver, "-o", binary]
  observed <- run "native-oracle" [] (root </> binary) []
  unless (BS.lines (commandStdout observed) == ["0\t0", "100\t100", "1000\t1000", "5000\t5000", "20000\t20000"])
    (die "Deep evaluation native oracle changed")
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    mapM_ (createDirectoryIfMissing True . (root </>)) [core, ghcOut]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "bin/export-core.sh" (options ++ ["-fplugin-opt=THC.Plugin:closure=probe", source])
    audited <- run (stage ++ "-audit") [] "python3"
      ["bin/audit-core.py", "--entry", "probe", "--output", directory </> stage </> "audit.json",
       core </> "DeepEvaluation.json", core </> "THC.InterfaceClosure.json"]
    pure [exported, audited]
  plugin <- listDirectory (root </> "src/compiler/THC")
  scripts <- listDirectory (root </> "bin")
  metadata <- eitherDecodeStrict' <$> BS.readFile (root </> "build/compiler/plugin.json")
  shared <- case metadata of
    Right (Object fields) | Just (String path) <- KeyMap.lookup "sharedLibrary" fields -> pure (makeRelative root (Text.unpack path))
    _ -> die "Deep evaluation requires the actual export plugin receipt"
  unless (isRelative shared && take 3 shared /= "../") (die "Export plugin must belong to this fixture checkout")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/DeepEvaluationFixtures.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "thc.cabal", "cabal.project", "bin/audit-core.py", "bin/core-capabilities.json",
        "bin/export-core.sh", "bin/build-compiler.sh", "bin/toolchain.sh", "bin/plugin.py"] ++
        ["src/compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["bin" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [version, compiled, observed] ++ concat stages
      artifacts = [binary, "build/compiler/plugin.json", shared] ++ concatMap commandArtifacts commands ++
        [directory </> stage </> file | stage <- ["pre", "post"],
          file <- ["core/DeepEvaluation.json", "core/THC.InterfaceClosure.json", "audit.json"]]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
     "oracle" .= (directory </> "logs/native-oracle.stdout"),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn "Prepared deep evaluation: 5 native rows, pre/post strict Core"
