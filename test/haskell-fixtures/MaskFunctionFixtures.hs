-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module MaskFunctionFixtures (prepareMaskFunctions) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareMaskFunctions :: FilePath -> IO ()
prepareMaskFunctions root = do
  let directory = "build/mask-functions"
      source = "compiler/test-fixtures/MaskFunctionAudit.hs"
      driver = "compiler/test-fixtures/MaskFunctionNative.hs"
      entries = ["maskedFunction", "unmaskedFunction", "uninterruptibleFunction", "lazyFunctions", "bareMasks"]
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
      native = directory </> "native"
      binary = native </> "oracle"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Mask functions require GHC 9.14.1")
  compiled <- run "native-compile" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint",
     "-i./compiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", binary]
  observed <- run "native-oracle" [] (root </> binary) []
  unless (length (BS.lines (commandStdout observed)) == 15) (die "Mask function oracle row count changed")
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"]
    mapM_ (createDirectoryIfMissing True . (root </>)) [core, ghcOut]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "compiler/export.sh" (options ++ [source])
    audits <- forM entries $ \entry -> run (stage ++ "-audit-" ++ entry) [] "python3"
      ["scripts/audit-core.py", "--entry", entry, "--output", directory </> stage </> entry ++ "-audit.json",
       core </> "MaskFunctionAudit.json"]
    pure (exported : audits)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/MaskFunctionFixtures.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "thc.cabal", "cabal.project", "scripts/audit-core.py", "scripts/core-capabilities.json",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"]
      commands = [version, compiled, observed] ++ concat stages
      artifacts = [binary] ++ concatMap commandArtifacts commands ++
        [directory </> stage </> "core/MaskFunctionAudit.json" | stage <- ["pre", "post"]] ++
        [directory </> stage </> entry ++ "-audit.json" | stage <- ["pre", "post"], entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "oracle" .= (directory </> "logs/native-oracle.stdout"),
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn "Prepared mask functions: 15 native rows, pre/post strict Core"
