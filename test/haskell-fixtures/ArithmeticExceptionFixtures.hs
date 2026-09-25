-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module ArithmeticExceptionFixtures (prepareArithmeticExceptions) where

import Control.Monad (forM, unless)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import Data.List (sort)
import qualified Data.Map.Strict as Map
import FixtureSupport (CommandResult(..), hashes, runLogged, writeJson)
import qualified THC.Driver.Wired as Wired
import System.Directory (createDirectoryIfMissing, listDirectory)
import System.Environment (getExecutablePath, lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)

prepareArithmeticExceptions :: FilePath -> IO ()
prepareArithmeticExceptions root = do
  let directory = "build/arithmetic-exceptions"
      source = "compiler/test-fixtures/ArithmeticExceptionsAudit.hs"
      driver = "compiler/test-fixtures/ArithmeticExceptionsNative.hs"
      entries = ["scalarDivZero", "scalarOverflow", "scalarUnderflow", "tupleDivZero", "tupleOverflow", "tupleUnderflow"]
      run label env program args = runLogged 180 root (directory </> "logs") label env program args
      native = directory </> "native"
      binary = native </> "oracle"
  createDirectoryIfMissing True (root </> native)
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Arithmetic exceptions require GHC 9.14.1")
  compiled <- run "native-compile" [] ghc
    ["--make", "-O2", "-dynamic", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-package", "ghc-internal",
     "-i./compiler/test-fixtures", "-odir", native, "-hidir", native, driver, "-o", binary]
  observed <- run "native-oracle" [] (root </> binary) []
  unless (length (BS.lines (commandStdout observed)) == 42) (die "Arithmetic exception oracle row count changed")
  pluginBuild <- run "plugin-build" [] "compiler/build.sh" []
  executable <- getExecutablePath
  -- Reuse the existing original-source exporter, including the real
  -- SomeException, Exception dictionary, Typeable and exception context code.
  originalExport <- runLogged 600 root (directory </> "logs") "original-source-export" [] executable
    ["original-stack-source-export", directory </> "originals"]
  let originalCore = directory </> "originals/core"
  originals <- map (originalCore </>) . sort <$> listDirectory (root </> originalCore)
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage </> "core"
        ghcOut = directory </> stage </> "ghc"
        options = ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          ["-fplugin-opt=THC.Plugin:closure=" ++ entry | entry <- entries]
        modules = (core </> "ArithmeticExceptionsAudit.json") : originals
    mapM_ (createDirectoryIfMissing True . (root </>)) [core, ghcOut]
    exported <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> ghcOut)]
      "compiler/export.sh" (["-package", "ghc-internal"] ++ options ++ [source])
    audits <- forM entries $ \entry -> run (stage ++ "-audit-" ++ entry) [] "python3"
      (["scripts/audit-core.py", "--entry", entry, "--output", directory </> stage </> entry ++ "-audit.json"] ++ modules)
    pure (stage, modules, exported : audits)
  plugin <- listDirectory (root </> "compiler/THC")
  scripts <- listDirectory (root </> "scripts")
  let inputs = sort $ [source, driver, "test/haskell-fixtures/ArithmeticExceptionFixtures.hs",
        "test/haskell-fixtures/StackFixtures.hs", "src/THC/Driver/Wired.hs",
        "test/haskell-fixtures/Main.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "thc.cabal", "cabal.project", "scripts/audit-core.py", "scripts/core-capabilities.json",
        "src/main/resources/thc/scalar-primop-signatures.json", "compiler/target-layout.c",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/plugin.py"] ++
        ["compiler/THC" </> file | file <- plugin, takeExtension file == ".hs"] ++
        ["scripts" </> file | file <- scripts, take 5 file == "core_", takeExtension file == ".py"] ++
        ["compiler/pinned-ghc-internal" </> file | (file,_) <- Wired.sourceHashes]
      commands = [version, compiled, observed, pluginBuild, originalExport] ++ concat [cs | (_,_,cs) <- stages]
      artifacts = [binary, directory </> "originals/generated.json"] ++ concatMap commandArtifacts commands ++
        originals ++ [directory </> stage </> "core/ArithmeticExceptionsAudit.json" | stage <- ["pre", "post"]] ++
        [directory </> stage </> "core/THC.InterfaceClosure.json" | stage <- ["pre", "post"]] ++
        [directory </> stage </> entry ++ "-audit.json" | stage <- ["pre", "post"], entry <- entries]
  inputHashes <- hashes root inputs
  artifactHashes <- hashes root artifacts
  writeJson (root </> directory </> "manifest.json") $ object
    ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String), "entries" .= entries,
     "oracle" .= (directory </> "logs/native-oracle.stdout"),
     "stages" .= Map.fromList [(stage, modules) | (stage, modules, _) <- stages],
     "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands, "runtimeVerified" .= False]
  putStrLn "Prepared arithmetic exceptions: 42 native rows, original SomeException dependencies, pre/post strict Core"
