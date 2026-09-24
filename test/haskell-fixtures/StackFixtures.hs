-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE OverloadedStrings #-}

module StackFixtures (prepareOriginalStack) where

import Control.Monad (forM, unless, when)
import Data.Aeson (object, (.=))
import qualified Data.ByteString.Char8 as BS
import qualified Data.Map.Strict as Map
import Data.List (sort)
import FixtureSupport (CommandResult (..), hashFile, hashes, runLogged, writeJson)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, renameFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

entries :: [String]
entries = ["captureOriginal", "decodeOriginal", "renderOriginal", "peekOriginalInfoTable",
           "lookupOriginalIPE", "peekOriginalInfoProv"]

proofResource :: FilePath
proofResource = "compiler/test-fixtures/OriginalStackProof.json"

prepareOriginalStack :: FilePath -> IO ()
prepareOriginalStack root = do
  let base = "build/original-stack"
      manifest = root </> base </> "manifest.json"
  createDirectoryIfMissing True (root </> base)
  previous <- listDirectory (root </> base)
  let available n = if "run-" ++ show n `elem` previous then available (n + 1) else "run-" ++ show n
      attempt = available (1 :: Int)
      directory = base </> attempt
      logs = directory </> "logs"
      run label overrides program arguments = runLogged 180 root logs label overrides program arguments
  createDirectoryIfMissing True (root </> directory)
  present <- doesFileExist manifest
  when present (renameFile manifest (root </> directory </> "previous-manifest.json"))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- run "ghc-version" [] ghc ["--numeric-version"]
  unless (BS.words (commandStdout version) == ["9.14.1"]) (die "Original stack fixture requires GHC 9.14.1")
  revision <- run "thc-revision" [] "git" ["rev-parse", "HEAD"]

  let source = "compiler/test-fixtures/OriginalStackAudit.hs"
      driver = "compiler/test-fixtures/OriginalStackAuditNative.hs"
  stages <- forM ["pre", "post"] $ \stage -> do
    let core = directory </> stage ++ "-core"
        options = ["-package", "ghc-internal"] ++
          ["-fplugin-opt=THC.Plugin:post-tidy" | stage == "post"] ++
          map ("-fplugin-opt=THC.Plugin:closure=" ++) entries ++ [source]
    result <- run (stage ++ "-export")
      [("THC_CORE_OUT", root </> core), ("THC_GHC_OUT", root </> directory </> stage ++ "-ghc")]
      "compiler/export.sh" options
    paths <- map (core </>) . sort <$> listDirectory (root </> core)
    let json = filter (\p -> reverse (take 5 (reverse p)) == ".json") paths
    unless (core </> "OriginalStackAudit.json" `elem` json) (die "Missing original stack consumer export")
    pure (stage, json, result)

  let native = directory </> "native"
      executable = native </> "original-stack-native"
  createDirectoryIfMissing True (root </> native)
  compile <- run "native-compile" [] ghc
    ["--make", "-O2", "-fforce-recomp", "-dcore-lint", "-dstg-lint", "-g", "-finfo-table-map",
     "-package", "ghc-internal", "-i" ++ root </> "compiler/test-fixtures",
     "-odir", root </> native, "-hidir", root </> native, root </> driver, "-o", root </> executable]
  nativeRun <- run "native-invariants" [] (root </> executable) []

  let commands = [version, revision] ++ [r | (_, _, r) <- stages] ++ [compile, nativeRun]
      artifacts = concat [paths | (_, paths, _) <- stages] ++ [executable] ++
        concatMap commandArtifacts commands
      sources = [source, driver, "test/haskell-fixtures/StackFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "test/haskell-fixtures/Main.hs", "thc.cabal", proofResource,
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/THC/Plugin.hs",
        "compiler/THC/CBV.hs", "compiler/THC/Demands.hs", "compiler/THC/Sources.hs", "compiler/THC/Wired.hs", "compiler/plugin.py",
        "compiler/pinned-ghc-internal/LICENSE"] ++ map ("compiler/pinned-ghc-internal/" ++)
        ["GHC/Internal/Stack/CloneStack.hs", "GHC/Internal/Stack/Decode.hs",
         "GHC/Internal/InfoProv/Types.hsc", "GHC/Internal/Heap/InfoTable.hsc"]
  proofHash <- hashFile (root </> proofResource)
  unless (proofHash == "db63661c12a6ecb757697e759fcb95e4d51f3689619bdb7682a041788eb41d4f")
    (die "Original stack proof resource changed; review its original-source provenance")
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["format" .= ("thc-original-stack-fixture" :: String), "schema" .= (1 :: Int),
     "entries" .= entries, "thcRevision" .= BS.unpack (BS.takeWhile (/= '\n') (commandStdout revision)),
     "ghc" .= object ["path" .= ghc, "version" .= ("9.14.1" :: String), "installedArtifactsHashed" .= False],
     "stages" .= Map.fromList [(stage, paths) | (stage, paths, _) <- stages],
     "proofResource" .= proofResource, "nativeOutput" .= (logs </> "native-invariants.stdout"),
     "inputHashes" .= sourceHashes, "artifactHashes" .= artifactHashes,
     "commands" .= map commandRecord commands,
     "limit" .= ("Native invariants only; retained source contracts include unsupported cold getters. No guest Decode success or native/JVM frame equivalence." :: String)]
  putStrLn ("Original source consumer and native shape evidence: " ++ manifest)
