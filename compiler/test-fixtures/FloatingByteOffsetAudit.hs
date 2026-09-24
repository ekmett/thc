-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module FloatingByteOffsetAudit where

import GHC.Exts

-- Both stores and all four reads remain in one native/guest composite. The
-- offsets are bytes, deliberately unaligned to Float# and Double# widths.
{-# OPAQUE floatingByteOffsetBits #-}
floatingByteOffsetBits :: Word# -> Word# -> Int# -> Word#
floatingByteOffsetBits floatBits doubleBits selector = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, mutable #) ->
  case writeWord8ArrayAsFloat# mutable 1# (castWord32ToFloat# (wordToWord32# floatBits)) s1 of { s2 ->
  case writeWord8ArrayAsDouble# mutable 9# (castWord64ToDouble# (wordToWord64# doubleBits)) s2 of { s3 ->
  case unsafeFreezeByteArray# mutable s3 of { (# s4, bytes #) ->
    case selector of {
      0# -> word32ToWord# (castFloatToWord32# (indexWord8ArrayAsFloat# bytes 1#));
      1# -> case readWord8ArrayAsFloat# mutable 1# s4 of { (# _, value #) ->
        word32ToWord# (castFloatToWord32# value) };
      2# -> word64ToWord# (castDoubleToWord64# (indexWord8ArrayAsDouble# bytes 9#));
      _  -> case readWord8ArrayAsDouble# mutable 9# s4 of { (# _, value #) ->
        word64ToWord# (castDoubleToWord64# value) }
    }
  } } } })
