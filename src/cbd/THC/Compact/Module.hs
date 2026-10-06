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
  , writeModuleValue, encodeModuleValue, encodeModuleWithDebug, readModuleValue, readModuleMetadata, readModuleMetadataFile, readModuleSources, finalizeModuleMetadata
  ) where

import Control.Exception (bracket)
import Control.Monad (foldM, forM_, unless, void)
import Data.Aeson (Value)
import Data.Binary.Put (runPut)
import Data.Bits ((.&.), (.|.), complement)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Word (Word32, Word64)
import System.Directory (getTemporaryDirectory, removeFile)
import System.IO (hClose, openBinaryTempFile)
import THC.CoreSymbols (symbolDigest, encodeMd5Symbols)
import THC.Compact.Core
import THC.Compact.Compression
import THC.Compact.Annotations
import THC.Compact.Debug
import THC.Compact.Encode
import THC.Compact.Facts
import THC.Compact.Decode (decodeMetadata)
import THC.Compact.Inspect (inspectContainer, moduleJSON, unpackContainer)
import THC.Compact.JSON (parseModuleWithDebug, parseModuleFacts)
import THC.Compact.Wire
import THC.Compact.Writer
import THC.Compact.Zip (readZipHeader, readZipHeaderFile, finalizeHeaderFile)

-- | Import reference/golden values through the canonical typed encoder.
-- Production compiler publication supplies typed records directly.
writeModuleValue :: FilePath -> Value -> IO Container
writeModuleValue path value = do
  (facts,bindings,annotations) <- either fail pure (parseModuleWithDebug value)
  writeModuleWithDebug path facts bindings annotations

encodeModuleValue :: Value -> IO BS.ByteString
encodeModuleValue value = encoded (\path -> writeModuleValue path value)

-- | Encode original typed records through the same writer and ZIP assembly.
encodeModuleWithDebug :: Facts -> [(Binding,[Annotation])] -> ModuleAnnotations -> IO BS.ByteString
encodeModuleWithDebug facts bindings annotations = encoded (\path -> writeModuleWithDebug path facts bindings annotations)

-- | Build-time typed inspection. Serialized input is exclusively CBD.
readModuleValue :: BS.ByteString -> Either String Value
readModuleValue = inspectContainer

-- | Read module facts without decoding any executable binding.
readModuleMetadata :: BS.ByteString -> Either String (Header,Value)
readModuleMetadata bytes = do
  (header,facts) <- readFacts bytes
  pure (header,moduleJSON facts [])

readFacts :: BS.ByteString -> Either String (Header,Facts)
readFacts bytes = do
  (headerBytes,lengths) <- readZipHeader bytes
  parseFacts headerBytes lengths

-- | File-backed metadata observation reads no executable/debug payload bytes.
readModuleMetadataFile :: FilePath -> IO (Header,Value)
readModuleMetadataFile path = do
  (headerBytes,lengths) <- readZipHeaderFile path
  (header,facts) <- either fail pure (parseFacts headerBytes lengths)
  pure (header,moduleJSON facts [])

parseFacts :: BS.ByteString -> [Word64] -> Either String (Header,Facts)
parseFacts headerBytes lengths = do
  header <- decodeExact getHeader (BS.take 32 headerBytes)
  validateContainer header lengths
  facts <- decodeMetadata (BS.drop 32 headerBytes)
  pure (header,facts)

-- | Read exact persisted source observations without decoding executable
-- bindings, display names, or line/column records. An absent table yields no
-- observations, not invented files with unknown contents.
readModuleSources :: BS.ByteString -> Either String [SourceFile]
readModuleSources bytes = do
  (_,_,segments) <- unpackContainer bytes
  case segments of
    [payload,strings,_,filenames,_,_] -> sourceFiles filenames strings (fromIntegral (BS.length payload))
    _ -> Left "Compact container requires six segments"

-- | Finalize the native-link metadata after all obligations are known. Only
-- the final header and its private strings are encoded. The six preceding ZIP
-- entries retain exact local headers, compressed bytes, directories and offsets.
-- The path MUST name a private staging file, never a published cache artifact.
finalizeModuleMetadata :: FilePath -> Value -> IO ()
finalizeModuleMetadata path value = do
  updated <- either fail pure (parseModuleFacts value)
  finalizeHeaderFile path $ \original lengths -> do
    header <- either fail pure (decodeExact getHeader (BS.take 32 original))
    either fail pure (validateContainer header lengths)
    oldFacts <- either fail pure (decodeMetadata (BS.drop 32 original))
    -- Native linkage does not alter the original interface frontier or binder
    -- origins. Header-only callers never need synthetic executable bindings
    -- merely to carry that immutable provenance ledger through finalization.
    let facts = updated {factsClosureProvenance = factsClosureProvenance oldFacts,
          factsRecovery = factsRecovery oldFacts,
          factsBackendPolicy = case factsBackendPolicy updated of
            Nothing -> factsBackendPolicy oldFacts
            policy -> policy}
    if oldFacts == facts then pure Nothing else do
      metadata <- encodeMetadata (\streams -> newEncoder streams >>= \encoder -> encodeFacts encoder facts)
      let finalHeader = header {headerSummaries =
            (headerSummaries header .&. complement 10) .|. factSummaries facts}
      pure (Just (BL.toStrict (runPut (putHeader finalHeader)) <> metadata))

encoded :: (FilePath -> IO Container) -> IO BS.ByteString
encoded action = do
  directory <- getTemporaryDirectory
  bracket (openBinaryTempFile directory "thc-module.cbd")
    (\(path,_) -> removeFile path) $ \(path,handle) -> do
      hClose handle
      _ <- action path
      BS.readFile path

factSummaries :: Facts -> Word32
factSummaries facts = (if registration then 2 else 0) .|. (if declarations then 8 else 0) .|.
  (if factsRecovery facts == Nothing then 0 else 32)
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
  let prepare streams = do
        encoder <- newEncoder streams
        encodeFacts encoder facts
      produce streams = do
        encoder <- newEncoder streams
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
        recovery <- containsRecoveryFacts encoder
        let summaries = (if control then 1 else 0) .|.
              factSummaries facts .|. (if alias then 4 else 0) .|. (if hostSignatures then 16 else 0) .|. (if recovery then 32 else 0)
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
