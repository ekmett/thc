-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : LibdwUnavailableFixtures
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 compiler API
--
-- Fixture acquisition support for libdw unavailable.
module LibdwUnavailableFixtures (prepareLibdwUnavailable) where

import Control.Monad (unless, when)
import Control.Monad.IO.Class (liftIO)
import Data.Aeson (Value(..), object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import Data.Foldable (toList)
import Data.List (nub, sort)
import FixtureSupport (commandStdout, commandStderr, commandArtifacts, commandRecord, hashes, runLogged, writeJson)
import GHC
import GHC.Driver.Main (hscSimplify)
import THC.Compact.Module (readModuleValue)
import THC.Plugin (serializeOptimizedCoreCBD)
import THC.Driver.PackageNative (installedNativeSignatures, nativeWrapperSource)
import System.Directory (createDirectoryIfMissing, doesFileExist, listDirectory, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>), takeExtension)
import qualified System.Info as Host
import Text.Read (readMaybe)

prepareLibdwUnavailable :: FilePath -> IO ()
prepareLibdwUnavailable root = do
  let directory = "build/libdw-unavailable"
      native = directory </> "native"
      suffix = if Host.os == "mingw32" then ".exe" else ""
      way = ["-dynamic" | Host.os /= "mingw32"]
      binary = native </> "oracle" ++ suffix
      finalizerBinary = native </> "c-finalizer" ++ suffix
      outputLines = map (takeWhile (/= '\r')) . lines . BS.unpack
      source = "t/fixtures/compiler/LibdwUnavailableNative.hs"
      cFinalizerSource = "t/fixtures/compiler/CFinalizerNative.hs"
      labelSource = "t/fixtures/compiler/ForeignLabelAudit.hs"
      -- Manifest keys use repository-relative paths on every host.
      labelsFile = directory ++ "/foreign-labels.cbd"
      oracle = directory ++ "/oracle.json"
      manifest = directory </> "manifest.json"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (outputLines (commandStdout version) == ["9.14.1"]) (die "Libdw oracle requires GHC 9.14.1")
  built <- execute "native-build" [] ghc (["--make", "-O2", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, source, "-o", root </> binary] ++ way)
  observed <- execute "native-oracle" [] (root </> binary) []
  let parsed = case outputLines (commandStdout observed) of
        ["USE_LIBDW=0", observations] -> readMaybe observations :: Maybe [Bool]
        _ -> Nothing
  unless (parsed == Just (replicate 8 True) && BS.null (commandStderr observed))
    (die "Libdw oracle does not match unavailable RTS semantics")
  finalizerBuilt <- execute "c-finalizer-build" [] ghc (["--make", "-O2", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, cFinalizerSource, "-o", root </> finalizerBinary] ++ way)
  cFinalizers <- execute "c-finalizer-oracle" [] (root </> finalizerBinary) []
  let cParsed = case outputLines (commandStdout cFinalizers) of
        ["USE_LIBDW=0", observations] -> readMaybe observations :: Maybe [Bool]
        _ -> Nothing
  unless (cParsed == Just (replicate 18 True) && BS.null (commandStderr cFinalizers))
    (die "Original C finalizer oracle failed")
  library <- execute "ghc-libdir" [] ghc ["--print-libdir"]
  libdir <- case outputLines (commandStdout library) of
    [path] -> pure path
    _ -> die "Expected one GHC library directory"
  labels <- exportLabels libdir (root </> labelSource)
  BS.writeFile (root </> labelsFile) labels
  (providers, providerCommands) <- if Host.os /= "mingw32" then pure ([], []) else do
    -- GHC's original RTS headers describe MinGW. Compile only the scalar ABI
    -- adapters for Sulong's MSVC target; their callees remain the unchanged
    -- native USE_LIBDW=0 DLL, not copied or rewritten RTS implementations.
    original <- exportCore libdir (root </> source)
    let imports = directory </> "native-imports.cbd"
        adapterSource = directory </> "native/adapters.c"
        adapter = directory </> "native/adapters.bc"
        target = "x86_64-pc-windows-msvc19.33.0"
    BS.writeFile (root </> imports) original
    value <- either die pure (readModuleValue original)
    signatures <- either die pure (installedNativeSignatures "main" value)
    unless (length signatures == 4) (die "Original libdw native observer lost its four C imports")
    wrappers <- either die pure (nativeWrapperSource
      [(signature, "thc_libdw_adapter_" ++ symbol, Nothing) | signature@(symbol,_,_,_,_) <- signatures])
    BS.writeFile (root </> adapterSource) (BS.pack ("#include <stdint.h>\n" ++
      "_Static_assert(sizeof(void *) == 8 && sizeof(int32_t) == 4, \"unsupported libdw adapter ABI\");\n" ++ wrappers))
    clang <- maybe "clang" id <$> lookupEnv "THC_CLANG"
    selected <- execute "adapter-target" [] clang ["--target=" ++ target, "-dumpmachine"]
    unless (outputLines (commandStdout selected) == [target]) (die "Clang did not select the Sulong MSVC ABI")
    adapted <- execute "adapter-build" [] clang ["--target=" ++ target,"-O1","-g","-emit-llvm","-c",
      root </> adapterSource,"-o",root </> adapter]
    pure ([imports, adapterSource, adapter], [selected, adapted])
  writeJson (root </> oracle) $ object ["useLibdw" .= (False :: Bool), "observations" .= parsed,
    "cFinalizerObservations" .= cParsed]
  compilerFiles <- listDirectory (root </> "src/compiler/THC")
  compactFiles <- listDirectory (root </> "src/cbd/THC/Compact")
  inputHashes <- hashes root $ sort $ [source, cFinalizerSource, labelSource, "t/haskell-fixtures/LibdwUnavailableFixtures.hs",
    "src/core-symbols/THC/CoreSymbols.hs", "src/driver/THC/Driver/PackageNative.hs",
    "t/haskell-fixtures/FixtureSupport.hs", "t/haskell-fixtures/Main.hs", "thc.cabal"] ++
    ["src/compiler/THC" </> name | name <- compilerFiles, takeExtension name == ".hs"] ++
    ["src/cbd/THC/Compact" </> name | name <- compactFiles, takeExtension name == ".hs"]
  let commands = [version, built, observed, finalizerBuilt, cFinalizers, library] ++ providerCommands
  artifactHashes <- hashes root ([oracle, labelsFile] ++ providers ++ concatMap commandArtifacts commands)
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes, "commands" .= map commandRecord commands]
  putStrLn "libdw-unavailable: eight original calls, eighteen native C-finalizer checks, exact function/data labels"

-- Compile actual typed Core through the same serializer, with no native link
-- needed for the separate data-symbol obligation. No pretty-printed parsing.
exportLabels :: FilePath -> FilePath -> IO BS.ByteString
exportLabels libdir source = do
  encoded <- exportCore libdir source
  value <- either die pure (readModuleValue encoded)
  let labels (Array xs) = case toList xs of
        [String "lit", String kind, String symbol, Object meta]
          | kind == "function-addr" || kind == "data-addr" -> [(kind, symbol, KeyMap.lookup "rep" meta)]
        elements -> concatMap labels elements
      labels (Object fields) = concatMap labels (toList fields)
      labels _ = []
      found = labels value
      hasFinalizer (Array xs) = case toList xs of
        String "prim" : String "addCFinalizerToWeak#" : _ -> True
        elements -> any hasFinalizer elements
      hasFinalizer (Object fields) = any hasFinalizer (toList fields)
      hasFinalizer _ = False
      proof = Just (object ["kind" .= ("address" :: String), "primReps" .= ["AddrRep" :: String], "evaluated" .= True])
  unless (sort (nub [(kind, symbol) | (kind, symbol, _) <- found]) ==
    [("data-addr", "enabled_capabilities"), ("function-addr", "backtraceFree"),
     ("function-addr", "free"), ("function-addr", "libdwPoolRelease")] &&
    all (\(_, _, representation) -> representation == proof) found && hasFinalizer value)
    (die "GHC function/data labels or AddrRep certificates changed")
  pure encoded

exportCore :: FilePath -> FilePath -> IO BS.ByteString
exportCore libdir source = runGhc (Just libdir) $ do
    flags <- getSessionDynFlags
    _ <- setSessionDynFlags flags { ghcLink = NoLink }
    selected <- getSessionDynFlags
    target <- guessTarget source Nothing Nothing
    setTargets [target]
    graph <- depanal [] False
    summary <- case mgModSummaries graph of
      [selectedModule] -> pure selectedModule
      _ -> liftIO (die "Unexpected foreign-label fixture graph")
    parsed <- parseModule summary
    typed <- typecheckModule parsed
    desugared <- desugarModule typed
    environment <- getSession
    optimized <- liftIO (hscSimplify environment [] (coreModule desugared))
    liftIO (serializeOptimizedCoreCBD selected [] optimized)
