-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module Explicit64ArrayAudit where

import GHC.Exts

-- Both stores and both stateful reads execute on every call. The selector
-- chooses one of the four retained read/index results without masking bits.
{-# OPAQUE explicit64ArrayBits #-}
explicit64ArrayBits :: Int# -> Int# -> Int#
explicit64ArrayBits bits selector = runRW# (\s0 ->
  case newByteArray# 32# s0 of { (# s1, mutable #) ->
  case writeInt64Array# mutable 1# (intToInt64# bits) s1 of { s2 ->
  case writeWord64Array# mutable 2# (wordToWord64# (int2Word# bits)) s2 of { s3 ->
  case readInt64Array# mutable 1# s3 of { (# s4, signed #) ->
  case readWord64Array# mutable 2# s4 of { (# s5, unsigned #) ->
  case unsafeFreezeByteArray# mutable s5 of { (# s6, bytes #) ->
  case s6 of { _ -> case selector of {
    0# -> int64ToInt# signed;
    1# -> word2Int# (word64ToWord# unsigned);
    2# -> int64ToInt# (indexInt64Array# bytes 1#);
    _  -> word2Int# (word64ToWord# (indexWord64Array# bytes 2#))
  } }
  } } } } } })
