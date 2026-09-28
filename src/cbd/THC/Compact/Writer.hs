-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE BangPatterns #-}

-- |
-- Module      : THC.Compact.Writer
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; scoped binary temporary files and atomic rename
--
-- Counted fragment construction followed by bounded ordinary ZIP assembly.
-- Only the completed CBD archive is published; temporary fragments are scoped.
module THC.Compact.Writer
  ( Streams, streamOffset, appendBytes, appendRecord, writeContainer, writeContainerPrepared
  , writeContainerStreamed, writeContainerStreamedWith ) where

import Control.Exception (IOException, bracket, bracketOnError, catch)
import Control.Monad (unless)
import Data.Binary.Put (Put, runPut)
import Data.Bits ((.|.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Digest.CRC32 (crc32, crc32Update)
import Data.IORef (IORef, newIORef, readIORef, writeIORef)
import Data.Word (Word32, Word64)
import System.Directory (removeFile, renameFile)
import System.FilePath (takeDirectory)
import System.IO (Handle, hClose, openBinaryTempFile)
import THC.Compact.Compression
import THC.Compact.Wire
import THC.Compact.Zip

-- | Private append-only handles and strict relative byte counters. They are
-- valid only inside the callback passed to 'writeContainer'.
data Fragment = Fragment !Handle !(IORef Word64) !(IORef Word32)
newtype Streams = Streams [Fragment]

-- | The next relative byte position, without seeking any output handle.
streamOffset :: Streams -> Segment -> IO Word64
streamOffset (Streams streams) segment = case streams !! fromEnum segment of
  Fragment _ size _ -> readIORef size

-- | Append bytes and return their segment-relative starting position. Text
-- encoding, shape interning and fingerprint sorting belong to the typed encoder.
appendBytes :: Streams -> Segment -> BS.ByteString -> IO Word64
appendBytes (Streams streams) segment bytes = do
  let Fragment handle counter checksum = streams !! fromEnum segment
  start <- readIORef counter
  end <- checkedAdd start (fromIntegral (BS.length bytes))
  BS.hPut handle bytes
  writeIORef counter $! end
  old <- readIORef checksum
  writeIORef checksum $! crc32Update old bytes
  pure start

-- | Encode one bounded record without accumulating preceding executable data.
appendRecord :: Streams -> Segment -> Put -> IO Word64
appendRecord streams segment record = do
  start <- streamOffset streams segment
  mapM_ (appendBytes streams segment) (BL.toChunks (runPut record))
  pure start

-- | Write and atomically publish one container. Header facts are already known
-- and bounded; the callback streams records and returns the binding count.
-- Summary bits must be actual producer facts. Every owned temporary file is
-- closed and removed on failure; auxiliary files are also removed on success.
-- Existing output remains intact if construction or framing validation fails.
writeContainer :: FilePath -> BS.ByteString -> Word32 -> (Streams -> IO Word64) -> IO Container
writeContainer destination facts = writeContainerPrepared destination (const (pure facts))

-- | Prepare small known-start facts while interning their strings in the
-- auxiliary stream. Preparation cannot emit executable bytes. Header facts are
-- complete before the prefix is written; no output seek or fixup is needed.
writeContainerPrepared :: FilePath -> (Streams -> IO BS.ByteString) -> Word32 -> (Streams -> IO Word64) -> IO Container
writeContainerPrepared destination prepare summaries produce =
  writeContainerStreamed destination prepare $ \streams -> do
    count <- produce streams
    pure (count,summaries)

-- | Default all-STORED CBD publication. Actual summaries are accumulated during
-- executable emission, never discovered by scanning a completed body.
writeContainerStreamed :: FilePath -> (Streams -> IO BS.ByteString) -> (Streams -> IO (Word64,Word32)) -> IO Container
writeContainerStreamed = writeContainerStreamedWith defaultCompression

-- | Per-member compression changes packaging only. Every fragment's CRC and
-- size are accumulated while producing it, so STORED assembly needs one copy.
writeContainerStreamedWith :: Compression -> FilePath -> (Streams -> IO BS.ByteString) -> (Streams -> IO (Word64,Word32)) -> IO Container
writeContainerStreamedWith policy destination prepare produce = bracketOnError
  (openBinaryTempFile directory "compact-archive.tmp") cleanup $ \(path, output) -> do
    container <- withAuxiliaries 6 [] $ \auxiliaries -> do
      streams <- Streams <$> mapM (\handle -> Fragment handle <$> newIORef 0 <*> newIORef 0) auxiliaries
      facts <- prepare streams
      preparedData <- streamOffset streams ExecutableData
      unless (preparedData == 0) (fail "Compact fact preparation emitted executable data")
      (count,summaries) <- produce streams
      let Streams handles = streams
      lengths <- mapM (\(Fragment _ size _) -> readIORef size) handles
      let debug = case lengths of
            [_,_,names,files,positions,_] ->
              (if names == 0 then 0 else 1) .|.
              (if files == 0 then 0 else 2) .|.
              (if positions == 0 then 0 else 4)
            _ -> 0
          header = Header 1 0 summaries count debug
          result = Container header lengths
          headerBytes = BL.toStrict (runPut (putHeader header)) <> facts
          members = [DataMember,StringsMember,NamesMember,FilenamesMember,LineColumnsMember,SymbolsMember]
      either fail pure (validateContainer header lengths)
      sources <- mapM (\(member,Fragment handle size checksum) ->
        ZipSource (memberName member) (compressionLevel member policy) <$> readIORef size <*> readIORef checksum <*> pure handle)
        (zip members handles)
      bracket (openBinaryTempFile directory "compact-header.tmp") cleanup $ \(_,headerHandle) -> do
        BS.hPut headerHandle headerBytes
        writeZip directory output
          (ZipSource "header" (compressionLevel HeaderMember policy) (fromIntegral (BS.length headerBytes)) (crc32 headerBytes) headerHandle : sources)
      pure result
    hClose output
    renameFile path destination
    pure container
  where
    directory = takeDirectory destination
    withAuxiliaries :: Int -> [Handle] -> ([Handle] -> IO a) -> IO a
    withAuxiliaries 0 handles action = action (reverse handles)
    withAuxiliaries remaining handles action = bracket
      (openBinaryTempFile directory "compact-aux.tmp") cleanup $ \(_, handle) ->
        withAuxiliaries (remaining-1) (handle:handles) action

checkedAdd :: Word64 -> Word64 -> IO Word64
checkedAdd start size
  | size <= maxBound-start = pure $! start+size
  | otherwise = fail "Compact stream length exceeds uint64"

cleanup :: (FilePath, Handle) -> IO ()
cleanup (path, handle) = do
  hClose handle `catch` ignore
  removeFile path `catch` ignore
  where
    ignore :: IOException -> IO ()
    ignore _ = pure ()
