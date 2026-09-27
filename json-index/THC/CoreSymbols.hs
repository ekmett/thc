-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : THC.CoreSymbols
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; bytestring
--
-- Producer spelling of a sorted, directly searchable Core symbol directory.
module THC.CoreSymbols (encodeSymbols) where

import qualified Data.ByteString as BS
import qualified Data.ByteString.Builder as Builder
import qualified Data.ByteString.Lazy as BL
import Data.List (sortOn)
import Data.Word (Word64)

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
