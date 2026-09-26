-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module TextCbitsAudit (textMemchr, textMeasure) where

import GHC.Exts
import GHC.Word (Word8(W8#))
import qualified Data.Text.Internal.ArrayUtils as A
import qualified Data.Text.Internal.Measure as M

-- These call the installed original text declarations; no redeclared FFI or
-- Haskell substitute is used in the native oracle or exported Core.
textMemchr :: ByteArray# -> Word# -> Word# -> Word# -> Int#
textMemchr bytes off len byte =
  case A.memchr bytes (I# (word2Int# off)) (I# (word2Int# len)) (W8# (wordToWord8# byte)) of
    I# value -> value

textMeasure :: ByteArray# -> Word# -> Word# -> Word# -> Int#
textMeasure bytes off len count =
  case fromIntegral (M.measure_off bytes (fromIntegral (W# off))
    (fromIntegral (W# len)) (fromIntegral (W# count))) :: Int of
      I# value -> value
