-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Int32ByteOffsetAudit where

import GHC.Exts

-- One native/guest call retains both signed and unsigned writes and all four
-- reads. Offsets 1 and 9 are bytes and deliberately unaligned to 32 bits.
{-# OPAQUE int32ByteOffsetValues #-}
int32ByteOffsetValues :: Int# -> Word# -> Int# -> Int#
int32ByteOffsetValues signed unsigned selector = runRW# (\s0 ->
  case newByteArray# 24# s0 of { (# s1, mutable #) ->
  case writeWord8ArrayAsInt32# mutable 1# (intToInt32# signed) s1 of { s2 ->
  case writeWord8ArrayAsWord32# mutable 9# (wordToWord32# unsigned) s2 of { s3 ->
  case unsafeFreezeByteArray# mutable s3 of { (# s4, bytes #) ->
    case selector of {
      0# -> int32ToInt# (indexWord8ArrayAsInt32# bytes 1#);
      1# -> case readWord8ArrayAsInt32# mutable 1# s4 of { (# _, value #) -> int32ToInt# value };
      2# -> word2Int# (word32ToWord# (indexWord8ArrayAsWord32# bytes 9#));
      _  -> case readWord8ArrayAsWord32# mutable 9# s4 of { (# _, value #) ->
        word2Int# (word32ToWord# value) }
    }
  } } } })
