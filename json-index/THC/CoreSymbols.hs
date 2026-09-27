-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.CoreSymbols
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC fingerprint API; bytestring
--
-- Producer spelling of a sorted, directly searchable Core symbol directory.
module THC.CoreSymbols (encodeSymbols, symbolFormat, symbolDigest, encodeMd5Symbols) where

import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as Builder
import qualified Data.ByteString.Lazy as BL
import Data.List (sortOn)
import Data.Word (Word64)
import Foreign.Ptr (castPtr)
import GHC.Fingerprint (fingerprintData)
import GHC.Fingerprint.Type (Fingerprint(..))

-- | Explicit manifest discriminator for fixed-width unit directories.
symbolFormat :: String
symbolFormat = "md5-utf8-u64le-v1"

-- | Canonical MD5 of the exact logical binding ID's UTF-8 bytes. GHC's
-- @fingerprintString@ uses a different character encoding and is not this wire
-- format. The two fingerprint words spell the canonical digest in big endian.
symbolDigest :: BS.ByteString -> IO BS.ByteString
symbolDigest key = BS.useAsCStringLen key $ \(bytes, size) -> do
  Fingerprint high low <- fingerprintData (castPtr bytes) size
  pure $ BL.toStrict $ Builder.toLazyByteString $ Builder.word64BE high <> Builder.word64BE low

-- | Sorted fixed 24-byte records: 16 canonical digest bytes followed by the
-- absolute JSON byte offset as an unsigned little-endian 64-bit word. MD5 is
-- assumed collision-free for these IDs; no name table or collision machinery
-- is stored. Duplicate digests therefore denote duplicate binding IDs.
encodeMd5Symbols :: [(BS.ByteString, Word64)] -> Either String BS.ByteString
encodeMd5Symbols input
  | any ((/= 16) . BS.length) keys = Left "Core symbol digest requires 16 bytes"
  | or (zipWith (==) keys (drop 1 keys)) = Left "Duplicate Core binding ID in symbol directory"
  | otherwise = Right $ BL.toStrict $ Builder.toLazyByteString $ foldMap row sorted
  where
    sorted = sortOn fst input
    keys = map fst sorted
    row (key, offset) = Builder.byteString key <> Builder.word64LE offset

-- | Each key is the exact decoded binding ID encoded as UTF-8, not an escaped
-- JSON string or a display name. Rows are sorted by unsigned UTF-8 bytes and
-- contain @ID SPACE decimal-byte-offset NEWLINE@. Split at the last space:
-- spaces inside a symbol are preserved. Offsets address the opening object
-- brace in the final JSON file; the next row is not a binding's end offset.
encodeSymbols :: [(BS.ByteString, Word64)] -> Either String BS.ByteString
encodeSymbols input
  | any (\key -> BS.null key || BS.any (`elem` [10, 13]) key) keys =
      Left "Core symbol directory requires nonempty IDs without line breaks"
  | or (zipWith (==) keys (drop 1 keys)) = Left "Duplicate Core binding ID in symbol directory"
  | otherwise = Right $ BL.toStrict $ Builder.toLazyByteString $ foldMap row sorted
  where
    sorted = sortOn fst input
    keys = map fst sorted
    row (key, offset) = Builder.byteString key <> Builder.char7 ' ' <>
      Builder.word64Dec offset <> Builder.char7 '\n'
