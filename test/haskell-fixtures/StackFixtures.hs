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
import System.Directory (copyFile, createDirectoryIfMissing, doesFileExist, listDirectory, renameFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))

entries :: [String]
entries = ["captureOriginal", "decodeOriginal", "renderOriginal", "peekOriginalInfoTable",
           "lookupOriginalIPE", "peekOriginalInfoProv"]

-- Existing post-Tidy source exports, never relabelled as newly exported Core.
retained :: [(String, String)]
retained =
  [("GHC.Internal.Stack.CloneStack", "d0733836485a57ebc40a4ae52ce77319e4dbc44f617cbd396335ae977e5810e4"),
   ("GHC.Internal.Stack.Decode", "c3762b0e2ed8bb2bb50b748144fcc7da01dec204c0cc48adade79962e8b35c42"),
   ("GHC.Internal.InfoProv.Types", "63fe524cfd81c88ebd4f835c8718a30b86828c9e53549a2c001cbffac5ab2d1e"),
   ("GHC.Internal.Heap.InfoTable", "1065f91361835bb3cf0cf2547ee320c59ba542e65e26ef2ec55c297cdcf9855a")]

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

  sourceRoot <- lookupEnv "THC_STACK_RETAINED_ROOT" >>= maybe
    (die "Set THC_STACK_RETAINED_ROOT to the preserved source-exports directory; native/fresh export logs are retained") pure
  createDirectoryIfMissing True (root </> directory </> "retained")
  copies <- forM retained $ \(name, expected) -> do
    let original = sourceRoot </> name </> name ++ ".json"
        destination = directory </> "retained" </> name ++ ".json"
    actual <- hashFile original
    unless (actual == expected) (die ("Retained original source hash mismatch: " ++ original))
    copyFile original (root </> destination)
    pure (destination, object ["module" .= name, "originalPath" .= original,
      "path" .= destination, "sha256" .= expected,
      "exporterRevision" .= ("62e3400c5b889d3971cb4047709c408fd270255f" :: String),
      "ghcSourceRevision" .= ("902339d332fb4ce2b3c87dcac1ee6495d41ad886" :: String),
      "fresh" .= False])

  let commands = [version, revision] ++ [r | (_, _, r) <- stages] ++ [compile, nativeRun]
      artifacts = concat [paths | (_, paths, _) <- stages] ++ map fst copies ++ [executable] ++
        concatMap commandArtifacts commands
      sources = [source, driver, "test/haskell-fixtures/StackFixtures.hs", "test/haskell-fixtures/FixtureSupport.hs",
        "compiler/export.sh", "compiler/build.sh", "compiler/toolchain.sh", "compiler/THC/Plugin.hs"]
  sourceHashes <- hashes root sources
  artifactHashes <- hashes root artifacts
  writeJson manifest $ object
    ["format" .= ("thc-original-stack-fixture" :: String), "schema" .= (1 :: Int),
     "entries" .= entries, "thcRevision" .= BS.unpack (BS.takeWhile (/= '\n') (commandStdout revision)),
     "ghc" .= object ["path" .= ghc, "version" .= ("9.14.1" :: String), "installedArtifactsHashed" .= False],
     "stages" .= Map.fromList [(stage, paths) | (stage, paths, _) <- stages],
     "retained" .= map snd copies, "nativeOutput" .= (logs </> "native-invariants.stdout"),
     "sources" .= sourceHashes, "artifacts" .= artifactHashes,
     "commands" .= map commandRecord commands,
     "limit" .= ("Native invariants only; retained source contracts include unsupported cold getters. No guest Decode success or native/JVM frame equivalence." :: String)]
  putStrLn ("Original source consumer and native shape evidence: " ++ manifest)
