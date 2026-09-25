-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}
module LibdwUnavailableFixtures (prepareLibdwUnavailable) where

import Control.Monad (unless, when)
import Control.Monad.IO.Class (liftIO)
import Data.Aeson (Value(..), decodeStrict', object, (.=))
import qualified Data.Aeson.KeyMap as KeyMap
import qualified Data.ByteString.Char8 as BS
import Data.Foldable (toList)
import Data.List (sort)
import FixtureSupport (commandStdout, commandStderr, hashes, runLogged, writeJson)
import GHC
import GHC.Driver.Main (hscSimplify)
import THC.Plugin (serializeOptimizedCore)
import System.Directory (createDirectoryIfMissing, doesFileExist, removeFile)
import System.Environment (lookupEnv)
import System.Exit (die)
import System.FilePath ((</>))
import Text.Read (readMaybe)

prepareLibdwUnavailable :: FilePath -> IO ()
prepareLibdwUnavailable root = do
  let directory = "build/libdw-unavailable"
      native = directory </> "native"
      binary = native </> "oracle"
      source = "compiler/test-fixtures/LibdwUnavailableNative.hs"
      cFinalizerSource = "compiler/test-fixtures/CFinalizerNative.hs"
      labelSource = "compiler/test-fixtures/ForeignLabelAudit.hs"
      labelsFile = directory </> "foreign-labels.json"
      oracle = directory </> "oracle.json"
      manifest = directory </> "manifest.json"
      execute = runLogged 120 root (directory </> "logs")
  createDirectoryIfMissing True (root </> native)
  present <- doesFileExist (root </> manifest)
  when present (removeFile (root </> manifest))
  ghc <- maybe "ghc" id <$> lookupEnv "GHC"
  version <- execute "ghc-version" [] ghc ["--numeric-version"]
  unless (commandStdout version == "9.14.1\n") (die "Libdw oracle requires GHC 9.14.1")
  _ <- execute "native-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, source, "-o", root </> binary]
  observed <- execute "native-oracle" [] (root </> binary) []
  let parsed = case lines (BS.unpack (commandStdout observed)) of
        ["USE_LIBDW=0", observations] -> readMaybe observations :: Maybe [Bool]
        _ -> Nothing
  unless (parsed == Just (replicate 8 True) && BS.null (commandStderr observed))
    (die "Libdw oracle does not match unavailable RTS semantics")
  _ <- execute "c-finalizer-build" [] ghc ["--make", "-O2", "-dynamic", "-fforce-recomp", "-Wall", "-Werror",
    "-dcore-lint", "-dstg-lint", "-package", "ghc-internal", "-odir", root </> native,
    "-hidir", root </> native, cFinalizerSource, "-o", root </> native </> "c-finalizer"]
  cFinalizers <- execute "c-finalizer-oracle" [] (root </> native </> "c-finalizer") []
  let cParsed = case lines (BS.unpack (commandStdout cFinalizers)) of
        ["USE_LIBDW=0", observations] -> readMaybe observations :: Maybe [Bool]
        _ -> Nothing
  unless (cParsed == Just (replicate 14 True) && BS.null (commandStderr cFinalizers))
    (die "Original C finalizer oracle failed")
  library <- execute "ghc-libdir" [] ghc ["--print-libdir"]
  libdir <- case lines (BS.unpack (commandStdout library)) of
    [path] -> pure path
    _ -> die "Expected one GHC library directory"
  labels <- exportLabels libdir (root </> labelSource)
  writeJson (root </> labelsFile) labels
  writeJson (root </> oracle) $ object ["useLibdw" .= (False :: Bool), "observations" .= parsed,
    "cFinalizerObservations" .= cParsed]
  inputHashes <- hashes root [source, cFinalizerSource, labelSource, "test/haskell-fixtures/LibdwUnavailableFixtures.hs",
    "compiler/THC/Plugin.hs", "compiler/THC/CBV.hs", "compiler/THC/Demands.hs", "compiler/THC/Sources.hs", "compiler/THC/Wired.hs",
    "test/haskell-fixtures/FixtureSupport.hs", "test/haskell-fixtures/Main.hs", "thc.cabal"]
  artifactHashes <- hashes root [oracle, labelsFile]
  writeJson (root </> manifest) $ object ["schema" .= (1 :: Int), "ghc" .= ("9.14.1" :: String),
    "inputHashes" .= inputHashes, "artifactHashes" .= artifactHashes]
  putStrLn "libdw-unavailable: eight original calls, fourteen native C-finalizer checks, exact function/data labels"

-- Compile actual typed Core through the same serializer, with no native link
-- needed for the separate data-symbol obligation. No pretty-printed parsing.
exportLabels :: FilePath -> FilePath -> IO Value
exportLabels libdir source = do
  encoded <- runGhc (Just libdir) $ do
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
    liftIO (serializeOptimizedCore selected [] optimized)
  value <- maybe (die "Invalid serialized foreign-label Core") pure (decodeStrict' (BS.pack encoded))
  let labels (Array xs) = case toList xs of
        [String "lit", String kind, String symbol, Object meta]
          | kind == "function-addr" || kind == "data-addr" -> [(kind, symbol, KeyMap.lookup "rep" meta)]
        elements -> concatMap labels elements
      labels (Object fields) = concatMap labels (toList fields)
      labels _ = []
      found = labels value
      proof = Just (object ["kind" .= ("address" :: String), "primReps" .= ["AddrRep" :: String], "evaluated" .= True])
  unless (sort [(kind, symbol) | (kind, symbol, _) <- found] ==
    [("data-addr", "enabled_capabilities"), ("function-addr", "backtraceFree"), ("function-addr", "libdwPoolRelease")] &&
    all (\(_, _, representation) -> representation == proof) found)
    (die "GHC function/data labels or AddrRep certificates changed")
  pure value
