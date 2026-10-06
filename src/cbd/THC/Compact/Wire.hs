-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE OverloadedStrings #-}

-- |
-- Module      : THC.Compact.Wire
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; binary and fixed-width integers
--
-- Byte-exact primitives and bounded framing for the compact Core container.
-- This layer reads no executable records, strings or debug segments eagerly.
module THC.Compact.Wire
  ( Span(..), Segment(..), Header(..), Container(..)
  , headerSize, putUVar, getUVar, putSVar, getSVar
  , putSpan, getSpan, checkedSpan, putHeader, getHeader
  , validateContainer, decodeExact
  ) where

import Control.Monad (unless)
import Data.Binary.Get
import Data.Binary.Put
import Data.Bits ((.&.), (.|.), shiftL, shiftR, xor)
import qualified Data.ByteString as BS
import qualified Data.ByteString.Lazy as BL
import Data.Int (Int64)
import Data.Word (Word16, Word32, Word64)

-- | A half-open byte interval, never a character count. Internal references
-- are relative to their declared ZIP member.
data Span = Span { spanStart :: !Word64, spanLength :: !Word64 }
  deriving (Eq, Show)

-- | Physical assembly order. Each auxiliary stream can be built independently.
data Segment = ExecutableData | CommonStrings | RealNames | FilenameIntervals
  | LineColumnIntervals | Fingerprints
  deriving (Eq, Ord, Enum, Bounded, Show)

-- | The header member's fixed prefix; private strings and typed facts follow.
data Header = Header
  { headerMajor :: !Word16
  , headerMinor :: !Word16
  , headerSummaries :: !Word32
  , headerBindingCount :: !Word64
  , headerDebugFlags :: !Word32
  } deriving (Eq, Show)

-- | Publication result, not a serialized footer. Lengths are uncompressed and
-- follow 'Segment' order; ZIP owns all physical placement and compression.
data Container = Container
  { containerHeader :: !Header
  , containerLengths :: ![Word64]
  } deriving (Eq, Show)

headerSize :: Word64
headerSize = 32

-- | Canonical unsigned LEB128; all 64 bits are representable.
putUVar :: Word64 -> Put
putUVar value
  | value < 128 = putWord8 (fromIntegral value)
  | otherwise = putWord8 (fromIntegral value .&. 127 .|. 128) >> putUVar (value `shiftR` 7)

-- | Reject truncated, overlong and overflowing encodings locally.
getUVar :: Get Word64
getUVar = go 0 0
  where
    go shift acc = do
      byte <- getWord8
      let payload = byte .&. 127
      unless (shift < 63 || payload <= 1) (fail "Compact integer overflows uint64")
      let result = acc .|. (fromIntegral payload `shiftL` shift)
      if byte .&. 128 == 0 then do
        unless (shift == 0 || payload /= 0) (fail "Noncanonical compact integer")
        pure result
      else do
        unless (shift < 63) (fail "Compact integer exceeds ten bytes")
        go (shift+7) result

-- | Zigzag followed by canonical ULEB128, including both Int64 endpoints.
putSVar :: Int64 -> Put
putSVar value = putUVar (fromIntegral (value `shiftL` 1) `xor` fromIntegral (value `shiftR` 63))

getSVar :: Get Int64
getSVar = do
  value <- getUVar
  pure (fromIntegral (value `shiftR` 1) `xor` negate (fromIntegral (value .&. 1)))

putSpan :: Span -> Put
putSpan (Span start size) = putUVar start >> putUVar size

getSpan :: Get Span
getSpan = Span <$> getUVar <*> getUVar

-- | Subtraction-based bounds check avoids overflow in @start + length@.
checkedSpan :: Word64 -> Span -> Either String ()
checkedSpan extent (Span start size)
  | start <= extent && size <= extent-start = Right ()
  | otherwise = Left "Compact span exceeds its segment"

putHeader :: Header -> Put
putHeader header = do
  putByteString "THCCBD1\0"
  putWord16le (headerMajor header)
  putWord16le (headerMinor header)
  putWord32le (headerSummaries header)
  putWord64le (headerBindingCount header)
  putWord32le (headerDebugFlags header)
  putWord32le 0

getHeader :: Get Header
getHeader = do
  magic <- getByteString 8
  unless (magic == "THCCBD1\0") (fail "Invalid CBD header magic")
  major <- getWord16le
  minor <- getWord16le
  unless (major == 1 && minor == 2) (fail "Unsupported compact Core version")
  summaries <- getWord32le
  count <- getWord64le
  debug <- getWord32le
  reserved <- getWord32le
  unless (reserved == 0) (fail "Reserved CBD header bits are set")
  unless (summaries .&. 63 == summaries) (fail "Unknown compact summary bits")
  unless (debug .&. 7 == debug) (fail "Unknown compact debug bits")
  pure (Header major minor summaries count debug)

-- | Validate only bounded framing and range arithmetic. This does not hash,
-- scan or decode the executable payload or any debug segment.
validateContainer :: Header -> [Word64] -> Either String ()
validateContainer header lengths = do
  unless (headerMajor header == 1 && headerMinor header == 2) (Left "Unsupported compact Core version")
  case lengths of
    [_,_,names,filenames,lineColumns,fingerprints] -> do
      unless (fingerprints `mod` 24 == 0 &&
        headerBindingCount header == fingerprints `div` 24)
        (Left "Compact fingerprint extent differs from binding count")
      let flags = (if names > 0 then 1 else 0) .|.
            (if filenames > 0 then 2 else 0) .|.
            (if lineColumns > 0 then 4 else 0)
      unless (headerDebugFlags header == flags) (Left "Compact debug flags differ from segment extents")
      unless (headerSummaries header .&. 63 == headerSummaries header) (Left "Unknown compact summary bits")
    _ -> Left "CBD requires six payload members"

-- | Run a bounded field decoder and reject trailing bytes. A caller slices
-- only the requested header or selected record before using this.
decodeExact :: Get a -> BS.ByteString -> Either String a
decodeExact decoder bytes = case runGetOrFail decoder (BL.fromStrict bytes) of
  Left (_,_,problem) -> Left problem
  Right (rest,_,value)
    | BL.null rest -> Right value
    | otherwise -> Left "Trailing compact record bytes"
