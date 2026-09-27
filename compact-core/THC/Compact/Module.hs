-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE BangPatterns #-}
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.Module
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC fingerprint API; scoped binary output
--
-- One-pass typed module publication. Binding positions are captured as bytes
-- are written; the existing canonical MD5 encoder sorts only lookup records.
module THC.Compact.Module (writeModule, writeModuleWithDebug) where

import Control.Monad (foldM, forM_, unless, void)
import Data.Bits ((.|.))
import Data.IORef
import THC.CoreSymbols (symbolDigest, encodeMd5Symbols)
import THC.Compact.Core
import THC.Compact.Annotations
import THC.Compact.Debug
import THC.Compact.Encode
import THC.Compact.Facts
import THC.Compact.Wire
import THC.Compact.Writer

-- | Publish typed records with no debug streams. Callers must explicitly choose
-- this debug-free path; original debug information is not parsed or discarded
-- by this function. Module-level known unsupported provenance fails preparation.
writeModule :: FilePath -> Facts -> [Binding] -> IO Footer
writeModule destination facts bindings = writeModuleRecords destination facts
  (map (\value -> (value,[])) bindings) Nothing

-- | Preserve supplied original display annotations in optional debug segments,
-- using actual emitted DATA origins. No debug values alter semantic records.
writeModuleWithDebug :: FilePath -> Facts -> [(Binding,[Annotation])] -> ModuleAnnotations -> IO Footer
writeModuleWithDebug destination facts bindings catalog = writeModuleRecords destination facts bindings (Just catalog)

writeModuleRecords :: FilePath -> Facts -> [(Binding,[Annotation])] -> Maybe ModuleAnnotations -> IO Footer
writeModuleRecords destination facts bindings catalog = do
  encoderSlot <- newIORef Nothing
  let prepare streams = do
        encoder <- newEncoder streams
        writeIORef encoderSlot (Just encoder)
        encodeFacts encoder facts
      produce streams = do
        encoder <- readIORef encoderSlot >>= maybe (fail "Missing prepared compact encoder") pure
        debug <- traverse (const (newDebugEncoder streams (internString encoder))) catalog
        forM_ debug $ \tables -> forM_ catalog $ \annotations ->
          forM_ (constructorNames annotations) $ \(index,name) -> do
            unless (index < maxBound) (fail "Compact constructor debug name ordinal overflow")
            recordName tables maxBound (index+1) name
        (!count,!alias,rows) <- foldM (binding encoder debug) (0,False,[]) bindings
        dataEnd <- streamOffset streams ExecutableData
        mapM_ (\value -> finishDebug value dataEnd) debug
        directory <- either fail pure (encodeMd5Symbols rows)
        void (appendBytes streams Fingerprints directory)
        control <- containsDelimitedControl encoder
        let summaries = (if control then 1 else 0) .|.
              (if registration then 2 else 0) .|. (if alias then 4 else 0) .|.
              (if declarations then 8 else 0)
        pure (count,summaries)
      binding encoder debug (!count,!alias,rows) (value,annotations) = do
        key <- case bindingIdentity value of
          Global global -> pure global
          Local _ -> fail "Compact top-level binding requires a global identity"
        unless (count < maxBound) (fail "Compact binding count exceeds uint64")
        completed <- case (debug,catalog) of
          (Just tables,Just sources) -> do
            (observer,finish) <- annotationObserver tables (annotationSources sources) annotations
            setRecordObserver encoder (Just observer)
            pure finish
          _ -> pure (pure ())
        offset <- encodeBinding encoder value
        completed
        setRecordObserver encoder Nothing
        digest <- symbolDigest key
        let !isAlias = alias || key == "main::" <> factsModule facts <> ".main"
        pure (count+1,isAlias,(digest,offset):rows)
      registration = case factsForeign facts of
        Known (ForeignArtifacts _ _ stubs files) -> not (null files) || case stubs of
          Known (Stubs _ _ initializers finalizers) -> not (null initializers && null finalizers)
          _ -> False
        _ -> False
      declarations = case drop 2 (factsPendingProvenance facts) of
        Known (ImportsRecord (ImportProof _ _ _ _ _ _ (ImportsVerified _ _ imports _))) : _ -> not (null imports)
        _ -> False
  writeContainerStreamed destination prepare produce
