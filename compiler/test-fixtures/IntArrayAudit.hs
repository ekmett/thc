-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module IntArrayAudit where
import GHC.Exts

-- The preparation contract is a 64-bit Int host. Indices below are elements;
-- newByteArray# sizes and Word8 indices are bytes. No optimizer fences.
orderedInts :: Int# -> Int#
orderedInts seed = runRW# (\s0 ->
  case newByteArray# 24# s0 of { (# s1, a #) ->
  case newByteArray# 8# s1 of { (# s2, b #) ->
  case writeIntArray# a 0# seed s2 of { s3 ->
  case writeIntArray# a 1# (seed +# 1#) s3 of { s4 ->
  case writeIntArray# a 2# (seed `xorI#` 0x55aa55aa55aa55aa#) s4 of { s5 ->
  case writeIntArray# b 0# (seed +# 71#) s5 of { s6 ->
  case readIntArray# a 1# s6 of { (# s7, before #) ->
  case writeIntArray# a 1# (before +# 16#) s7 of { s8 ->
  case readIntArray# a 1# s8 of { (# s9, after #) ->
  case unsafeFreezeByteArray# a s9 of { (# s10, aa #) ->
  case unsafeFreezeByteArray# b s10 of { (# _, bb #) ->
    before *# 3# +# after *# 5# +#
    indexIntArray# aa 0# *# 7# +# indexIntArray# aa 1# *# 11# +#
    indexIntArray# aa 2# *# 13# +# indexIntArray# bb 0# *# 17# +#
    sizeofByteArray# aa +# sizeofByteArray# bb
  } } } } } } } } } } })

-- The two byte writes straddle the boundary between adjacent Int elements.
-- This must observe the same storage through both Int and Word8 operations.
aliasIntBytes :: Int# -> Int#
aliasIntBytes seed = runRW# (\s0 ->
  case newByteArray# 16# s0 of { (# s1, a #) ->
  case writeIntArray# a 0# seed s1 of { s2 ->
  case writeIntArray# a 1# (seed `xorI#` 0x55aa55aa55aa55aa#) s2 of { s3 ->
  case writeWord8Array# a 7# (wordToWord8# (int2Word# (seed +# 101#))) s3 of { s4 ->
  case writeWord8Array# a 8# (wordToWord8# (int2Word# (seed +# 37#))) s4 of { s5 ->
  case readIntArray# a 0# s5 of { (# s6, first #) ->
  case readIntArray# a 1# s6 of { (# s7, second #) ->
  case unsafeFreezeByteArray# a s7 of { (# _, frozen #) ->
    first *# 3# +# second *# 5# +#
    indexIntArray# frozen 0# *# 7# +# indexIntArray# frozen 1# *# 11# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 0#)) *# 17# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 7#)) *# 19# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 8#)) *# 23# +#
    word2Int# (word8ToWord# (indexWord8Array# frozen 15#)) *# 29#
  } } } } } } } })
