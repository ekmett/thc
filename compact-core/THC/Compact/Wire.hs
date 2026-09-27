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
  ( Span(..), Segment(..), Header(..), Footer(..)
  , headerSize, footerSize, putUVar, getUVar, putSVar, getSVar
  , putSpan, getSpan, checkedSpan, putHeader, getHeader, putFooter, getFooter
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
-- are relative to their declared segment; only footer directory spans are
-- absolute positions in the container.
data Span = Span { spanStart :: !Word64, spanLength :: !Word64 }
  deriving (Eq, Show)

-- | Physical assembly order. Each auxiliary stream can be built independently.
data Segment = ExecutableData | CommonStrings | RealNames | FilenameIntervals
  | LineColumnIntervals | Fingerprints
  deriving (Eq, Ord, Enum, Bounded, Show)

-- | Known-start framing. Typed module/compiler/layout/provenance facts follow
-- this fixed prefix, then the executable data segment begins.
data Header = Header
  { headerMajor :: !Word16
  , headerMinor :: !Word16
  , headerFactsLength :: !Word64
  } deriving (Eq, Show)

-- | End-derived directory and summaries, located directly from EOF. The six
-- spans must appear in 'Segment' order; the producer performs no header patch.
data Footer = Footer
  { footerSegments :: ![Span]
  , footerBindingCount :: !Word64
  , footerSummaries :: !Word32
  , footerDebugFlags :: !Word32
  } deriving (Eq, Show)

headerSize, footerSize :: Word64
headerSize = 24
footerSize = 128

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
  putByteString "THCCMP\0\0"
  putWord16le (headerMajor header)
  putWord16le (headerMinor header)
  putWord32le 0
  putWord64le (headerFactsLength header)

getHeader :: Get Header
getHeader = do
  magic <- getByteString 8
  unless (magic == "THCCMP\0\0") (fail "Invalid compact Core magic")
  major <- getWord16le
  minor <- getWord16le
  unless (major == 1 && minor == 0) (fail "Unsupported compact Core version")
  reserved <- getWord32le
  unless (reserved == 0) (fail "Reserved compact header bits are set")
  Header major minor <$> getWord64le

putFooter :: Footer -> Put
putFooter footer
  | length (footerSegments footer) /= 6 = error "Compact footer requires six segments"
  | otherwise = do
      putByteString "THCCEND1"
      mapM_ (\(Span start size) -> putWord64le start >> putWord64le size) (footerSegments footer)
      putWord64le (footerBindingCount footer)
      putWord32le (footerSummaries footer)
      putWord32le (footerDebugFlags footer)
      putWord64le 0

getFooter :: Get Footer
getFooter = do
  magic <- getByteString 8
  unless (magic == "THCCEND1") (fail "Invalid compact Core footer")
  spans <- sequence (replicate 6 (Span <$> getWord64le <*> getWord64le))
  count <- getWord64le
  summaries <- getWord32le
  debug <- getWord32le
  reserved <- getWord64le
  unless (reserved == 0) (fail "Reserved compact footer bits are set")
  unless (summaries .&. 15 == summaries) (fail "Unknown compact summary bits")
  unless (debug .&. 7 == debug) (fail "Unknown compact debug bits")
  pure (Footer spans count summaries debug)

-- | Validate only bounded framing and range arithmetic. This does not hash,
-- scan or decode the executable payload or any debug segment.
validateContainer :: Word64 -> Header -> Footer -> Either String ()
validateContainer fileSize header footer = do
  unless (headerMajor header == 1 && headerMinor header == 0) (Left "Unsupported compact Core version")
  unless (fileSize >= headerSize+footerSize) (Left "Truncated compact container")
  let bodyEnd = fileSize-footerSize
  checkedSpan bodyEnd (Span headerSize (headerFactsLength header))
  let dataStart = headerSize+headerFactsLength header
      spans = footerSegments footer
  unless (length spans == 6) (Left "Compact footer requires six segments")
  end <- contiguous bodyEnd dataStart spans
  unless (end == bodyEnd) (Left "Compact segments do not reach the footer")
  case spans of
    [_,_,names,filenames,lineColumns,fingerprints] -> do
      unless (spanLength fingerprints `mod` 24 == 0 &&
        footerBindingCount footer == spanLength fingerprints `div` 24)
        (Left "Compact fingerprint extent differs from binding count")
      let flags = (if spanLength names > 0 then 1 else 0) .|.
            (if spanLength filenames > 0 then 2 else 0) .|.
            (if spanLength lineColumns > 0 then 4 else 0)
      unless (footerDebugFlags footer == flags) (Left "Compact debug flags differ from segment extents")
      unless (footerSummaries footer .&. 15 == footerSummaries footer) (Left "Unknown compact summary bits")
    _ -> Left "Compact footer requires six segments"
  where
    contiguous _ position [] = Right position
    contiguous extent position (span' : rest) = do
      unless (spanStart span' == position) (Left "Compact segment directory is not contiguous")
      checkedSpan extent span'
      contiguous extent (position+spanLength span') rest

-- | Run a bounded field decoder and reject trailing bytes. A caller slices
-- only the requested header, footer or selected record before using this.
decodeExact :: Get a -> BS.ByteString -> Either String a
decodeExact decoder bytes = case runGetOrFail decoder (BL.fromStrict bytes) of
  Left (_,_,problem) -> Left problem
  Right (rest,_,value)
    | BL.null rest -> Right value
    | otherwise -> Left "Trailing compact record bytes"
