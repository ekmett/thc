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
module THC.Compact.Module
  ( writeModule, writeModuleWithDebug, writeModuleCompressed, writeModuleWithDebugCompressed
  , writeModuleValue, encodeModuleValue, readModuleValue, readModuleMetadata, rewriteModuleFacts
  ) where

import Control.Exception (bracket)
import Control.Monad (foldM, forM_, unless, void)
import Data.Aeson (Value)
import Data.Bits ((.&.), (.|.), complement)
import qualified Data.ByteString as BS
import Data.IORef
import Data.Word (Word32)
import System.Directory (getTemporaryDirectory, removeFile)
import System.IO (hClose, openBinaryTempFile)
import THC.CoreSymbols (symbolDigest, encodeMd5Symbols)
import THC.Compact.Core
import THC.Compact.Compression
import THC.Compact.Annotations
import THC.Compact.Debug
import THC.Compact.Encode
import THC.Compact.Facts
import THC.Compact.Decode (decodeFacts)
import THC.Compact.Inspect (inspectContainer, moduleJSON, unpackContainer)
import THC.Compact.JSON (parseModuleWithDebug, parseModuleFacts)
import THC.Compact.Wire
import THC.Compact.Writer

-- | The compiler's in-memory module value enters the existing typed encoder
-- directly. No JSON text, JSON file, or second binary codec is involved.
writeModuleValue :: FilePath -> Value -> IO Container
writeModuleValue path value = do
  (facts,bindings,annotations) <- either fail pure (parseModuleWithDebug value)
  writeModuleWithDebug path facts bindings annotations

encodeModuleValue :: Value -> IO BS.ByteString
encodeModuleValue value = encoded (\path -> writeModuleValue path value)

-- | Build-time typed inspection. Serialized input is exclusively CBD.
readModuleValue :: BS.ByteString -> Either String Value
readModuleValue = inspectContainer

-- | Read module facts without decoding any executable binding.
readModuleMetadata :: BS.ByteString -> Either String (Header,Value)
readModuleMetadata bytes = do
  (header,factsBytes,segments) <- unpackContainer bytes
  facts <- decodeFacts factsBytes (segments !! 1)
  pure (header,moduleJSON facts [])

-- | Native linkage changes header facts, not executable identities or debug
-- origins. Keep all DATA/debug/symbol bytes and original string offsets; append
-- strings needed by the new header. Unchanged facts retain the exact archive.
rewriteModuleFacts :: BS.ByteString -> Value -> IO BS.ByteString
rewriteModuleFacts original value = do
  (header,oldFactsBytes,segments) <- either fail pure (unpackContainer original)
  oldFacts <- either fail pure (decodeFacts oldFactsBytes (segments !! 1))
  facts <- either fail pure (parseModuleFacts value)
  if oldFacts == facts then pure original else encoded $ \path -> do
    let prepare streams = do
          void (appendBytes streams CommonStrings (segments !! 1))
          encoder <- newEncoder streams
          encodeFacts encoder facts
        produce streams = do
          forM_ (zip [minBound..maxBound] segments) $ \(segment,bytes) ->
            unless (segment == CommonStrings) (void (appendBytes streams segment bytes))
          pure (headerBindingCount header,
            (headerSummaries header .&. complement 10) .|. factSummaries facts)
    writeContainerStreamed path prepare produce

encoded :: (FilePath -> IO Container) -> IO BS.ByteString
encoded action = do
  directory <- getTemporaryDirectory
  bracket (openBinaryTempFile directory "thc-module.cbd")
    (\(path,_) -> removeFile path) $ \(path,handle) -> do
      hClose handle
      _ <- action path
      BS.readFile path

factSummaries :: Facts -> Word32
factSummaries facts = (if registration then 2 else 0) .|. (if declarations then 8 else 0)
  where
    registration = case factsForeign facts of
      Known (ForeignArtifacts _ _ stubs files) -> not (null files) || case stubs of
        Known (Stubs _ _ initializers finalizers) -> not (null initializers && null finalizers)
        _ -> False
      _ -> False
    declarations = any nativeLink (factsPendingProvenance facts) || case drop 2 (factsPendingProvenance facts) of
      Known (ImportsRecord (ImportProof _ _ _ _ _ _ (ImportsVerified _ _ imports _ addresses _ _))) : _ ->
        not (null imports && null addresses)
      _ -> False
    nativeLink (Known (NativeLinkRecord _)) = True
    nativeLink _ = False

-- | Publish typed records with no debug streams. Callers must explicitly choose
-- this debug-free path; original debug information is not parsed or discarded
-- by this function. Module-level known unsupported provenance fails preparation.
writeModule :: FilePath -> Facts -> [Binding] -> IO Container
writeModule = writeModuleCompressed defaultCompression

-- | Publish using an explicitly selected CBD compression policy.
writeModuleCompressed :: Compression -> FilePath -> Facts -> [Binding] -> IO Container
writeModuleCompressed policy destination facts bindings = writeModuleRecords policy destination facts
  (map (\value -> (value,[])) bindings) Nothing

-- | Preserve supplied original display annotations in optional debug segments,
-- using actual emitted DATA origins. No debug values alter semantic records.
writeModuleWithDebug :: FilePath -> Facts -> [(Binding,[Annotation])] -> ModuleAnnotations -> IO Container
writeModuleWithDebug = writeModuleWithDebugCompressed defaultCompression

-- | Preserve debug annotations while independently selecting ZIP methods.
writeModuleWithDebugCompressed :: Compression -> FilePath -> Facts -> [(Binding,[Annotation])] -> ModuleAnnotations -> IO Container
writeModuleWithDebugCompressed policy destination facts bindings catalog = writeModuleRecords policy destination facts bindings (Just catalog)

writeModuleRecords :: Compression -> FilePath -> Facts -> [(Binding,[Annotation])] -> Maybe ModuleAnnotations -> IO Container
writeModuleRecords policy destination facts bindings catalog = do
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
        hostSignatures <- containsHostSignatures encoder
        let summaries = (if control then 1 else 0) .|.
              factSummaries facts .|. (if alias then 4 else 0) .|. (if hostSignatures then 16 else 0)
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
        let !isAlias = alias || key == "main::Main.main"
        pure (count+1,isAlias,(digest,offset):rows)
  writeContainerStreamedWith policy destination prepare produce
