-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module NarrowByteOffsetAudit where

import GHC.Exts

-- One native/guest call retains both signed and unsigned writes and all four
-- reads. Offsets 1 and 5 are bytes and deliberately unaligned to 16 bits.
{-# OPAQUE narrowByteOffsetValues #-}
narrowByteOffsetValues :: Int# -> Word# -> Int# -> Int#
narrowByteOffsetValues signed unsigned selector = runRW# (\s0 ->
  case newByteArray# 16# s0 of { (# s1, mutable #) ->
  case writeWord8ArrayAsInt16# mutable 1# (intToInt16# signed) s1 of { s2 ->
  case writeWord8ArrayAsWord16# mutable 5# (wordToWord16# unsigned) s2 of { s3 ->
  case unsafeFreezeByteArray# mutable s3 of { (# s4, bytes #) ->
    case selector of {
      0# -> int16ToInt# (indexWord8ArrayAsInt16# bytes 1#);
      1# -> case readWord8ArrayAsInt16# mutable 1# s4 of { (# _, value #) -> int16ToInt# value };
      2# -> word2Int# (word16ToWord# (indexWord8ArrayAsWord16# bytes 5#));
      _  -> case readWord8ArrayAsWord16# mutable 5# s4 of { (# _, value #) ->
        word2Int# (word16ToWord# value) }
    }
  } } } })
