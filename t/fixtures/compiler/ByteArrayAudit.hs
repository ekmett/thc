-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : ByteArrayAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for byte array audit Core and metadata.
module ByteArrayAudit where

import GHC.Exts

-- Public ShortByteString packing and slicing are covered by ShortByteStringSliceAudit.
-- Repeated writes to one location expose a stale final byte or wrong write order. A
-- separate allocation with different contents exposes accidental shared storage.
{-# OPAQUE orderedBytes #-}
orderedBytes :: Int# -> Int#
orderedBytes seed = runRW# (\s0 ->
  case newByteArray# 3# s0 of { (# s1, a #) ->
  case newByteArray# 1# s1 of { (# s2, b #) ->
  case writeWord8Array# a 0# (wordToWord8# (int2Word# seed)) s2 of { s3 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 1#))) s3 of { s4 ->
  case writeWord8Array# b 0# (wordToWord8# (int2Word# (seed +# 71#))) s4 of { s5 ->
  case writeWord8Array# a 2# (wordToWord8# (int2Word# (seed +# 2#))) s5 of { s6 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 17#))) s6 of { s7 ->
  case unsafeFreezeByteArray# a s7 of { (# s8, aa #) ->
  case unsafeFreezeByteArray# b s8 of { (# _, bb #) ->
    sizeofByteArray# aa +#
      word2Int# (word8ToWord# (indexWord8Array# aa 0#)) *# 1# +#
      word2Int# (word8ToWord# (indexWord8Array# aa 1#)) *# 257# +#
      word2Int# (word8ToWord# (indexWord8Array# aa 2#)) *# 65537# +#
      word2Int# (word8ToWord# (indexWord8Array# bb 0#)) *# 16777259#
  } } } } } } } } })

-- Distinct initialized source/destination arrays, dynamic contained subranges,
-- and a zero-length copy at both ends. Check unchanged source and destination
-- bytes outside the range as well as copied bytes.
{-# OPAQUE copiedBytes #-}
copiedBytes :: Int# -> Int#
copiedBytes seed = runRW# (\s0 ->
  case newByteArray# 4# s0 of { (# s1, a #) ->
  case newByteArray# 6# s1 of { (# s2, b #) ->
  case writeWord8Array# a 0# (wordToWord8# (int2Word# seed)) s2 of { s3 ->
  case writeWord8Array# a 1# (wordToWord8# (int2Word# (seed +# 17#))) s3 of { s4 ->
  case writeWord8Array# a 2# (wordToWord8# 0##) s4 of { s5 ->
  case writeWord8Array# a 3# (wordToWord8# 255##) s5 of { s6 ->
  case writeWord8Array# b 0# (wordToWord8# 11##) s6 of { s7 ->
  case writeWord8Array# b 1# (wordToWord8# 22##) s7 of { s8 ->
  case writeWord8Array# b 2# (wordToWord8# 33##) s8 of { s9 ->
  case writeWord8Array# b 3# (wordToWord8# 44##) s9 of { s10 ->
  case writeWord8Array# b 4# (wordToWord8# 55##) s10 of { s11 ->
  case writeWord8Array# b 5# (wordToWord8# 66##) s11 of { s12 ->
  case unsafeFreezeByteArray# a s12 of { (# s13, aa #) ->
  case copyByteArray# aa sourceOffset b destinationOffset count s13 of { s14 ->
  case copyByteArray# aa 4# b 6# 0# s14 of { s15 ->
  case unsafeFreezeByteArray# b s15 of { (# _, bb #) ->
    sizeofByteArray# aa +# sizeofByteArray# bb +# checksum aa +# 4362470401# *# checksum bb
  } } } } } } } } } } } } } } } })
  where
    key = word2Int# (and# (int2Word# seed) 1023##)
    sourceOffset = remInt# key 5#
    destinationOffset = remInt# (quotInt# key 5#) 7#
    requested = remInt# (quotInt# key 35#) 5#
    count = minInt requested (minInt (4# -# sourceOffset) (6# -# destinationOffset))
    minInt x y = case x <# y of 1# -> x; _ -> y

-- The checksum observes every byte; its size should not multiply the copy
-- fixture's compiled graph. The native oracle checks the same polynomial.
{-# OPAQUE checksum #-}
checksum :: ByteArray# -> Int#
checksum array = go 0# 1# 0#
  where
    go index weight total = case index ==# sizeofByteArray# array of
      1# -> total
      _ -> go (index +# 1#) (weight *# 257#)
        (total +# weight *# word2Int# (word8ToWord# (indexWord8Array# array index)))
