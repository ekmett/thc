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
-- Forward-only container construction. Five private auxiliary streams are
-- appended to the executable stream, followed by an EOF directory. The data
-- handle is never sought or patched. No construction sidecars are published.
module THC.Compact.Writer (Streams, streamOffset, appendBytes, appendRecord, writeContainer) where

import Control.Exception (IOException, bracket, bracketOnError, catch)
import Control.Monad (foldM, unless)
import Data.Binary.Put (Put, runPut)
import Data.Bits ((.|.))
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.IORef (IORef, newIORef, readIORef, writeIORef)
import Data.Word (Word32, Word64)
import System.Directory (removeFile, renameFile)
import System.FilePath (takeDirectory)
import System.IO (Handle, SeekMode(AbsoluteSeek), hClose, hFlush, hSeek, openBinaryTempFile)
import THC.Compact.Wire

-- | Private append-only handles and strict relative byte counters. They are
-- valid only inside the callback passed to 'writeContainer'.
newtype Streams = Streams [(Handle, IORef Word64)]

-- | The next relative byte position, without seeking any output handle.
streamOffset :: Streams -> Segment -> IO Word64
streamOffset (Streams streams) segment = readIORef (snd (streams !! fromEnum segment))

-- | Append bytes and return their segment-relative starting position. Text
-- encoding, shape interning and fingerprint sorting belong to the typed encoder.
appendBytes :: Streams -> Segment -> BS.ByteString -> IO Word64
appendBytes (Streams streams) segment bytes = do
  let (handle, counter) = streams !! fromEnum segment
  start <- readIORef counter
  end <- checkedAdd start (fromIntegral (BS.length bytes))
  BS.hPut handle bytes
  writeIORef counter $! end
  pure start

-- | Encode one bounded record without accumulating preceding executable data.
appendRecord :: Streams -> Segment -> Put -> IO Word64
appendRecord streams segment record = do
  let Streams handles = streams
  start <- readIORef (snd (handles !! fromEnum segment))
  mapM_ (appendBytes streams segment) (BL.toChunks (runPut record))
  pure start

-- | Write and atomically publish one container. Header facts are already known
-- and bounded; the callback streams records and returns the binding count.
-- Summary bits must be actual producer facts. Every owned temporary file is
-- closed and removed on failure; auxiliary files are also removed on success.
-- Existing output remains intact if construction or framing validation fails.
writeContainer :: FilePath -> BS.ByteString -> Word32 -> (Streams -> IO Word64) -> IO Footer
writeContainer destination facts summaries produce = bracketOnError
  (openBinaryTempFile directory "compact-data.tmp") cleanup $ \(path, output) -> do
    footer <- withAuxiliaries 5 [] $ \auxiliaries -> do
      let header = Header 1 0 (fromIntegral (BS.length facts))
      BL.hPut output (runPut (putHeader header))
      BS.hPut output facts
      streams <- Streams <$> mapM (\handle -> (,) handle <$> newIORef 0) (output : auxiliaries)
      count <- produce streams
      let Streams handles = streams
      lengths <- mapM (readIORef . snd) handles
      (_, spans) <- foldM nextSpan (headerSize+headerFactsLength header, []) lengths
      end <- case reverse spans of
        Span start size : _ -> checkedAdd start size
        [] -> fail "Missing compact streams"
      total <- checkedAdd end footerSize
      let debug = case lengths of
            [_,_,names,files,positions,_] ->
              (if names == 0 then 0 else 1) .|.
              (if files == 0 then 0 else 2) .|.
              (if positions == 0 then 0 else 4)
            _ -> 0
          result = Footer spans count summaries debug
      either fail pure (validateContainer total header result)
      mapM_ (copyAuxiliary output) auxiliaries
      BL.hPut output (runPut (putFooter result))
      pure result
    hClose output
    renameFile path destination
    pure footer
  where
    directory = takeDirectory destination
    withAuxiliaries :: Int -> [Handle] -> ([Handle] -> IO a) -> IO a
    withAuxiliaries 0 handles action = action (reverse handles)
    withAuxiliaries remaining handles action = bracket
      (openBinaryTempFile directory "compact-aux.tmp") cleanup $ \(_, handle) ->
        withAuxiliaries (remaining-1) (handle:handles) action
    nextSpan (!position, spans) size = do
      end <- checkedAdd position size
      pure (end, spans ++ [Span position size])

copyAuxiliary :: Handle -> Handle -> IO ()
copyAuxiliary output input = do
  hFlush input
  hSeek input AbsoluteSeek 0
  copy
  where
    copy = do
      bytes <- BS.hGetSome input 65536
      unless (BS.null bytes) (BS.hPut output bytes >> copy)

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
