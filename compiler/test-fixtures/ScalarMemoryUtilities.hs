-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ScalarMemoryUtilities where
import GHC.Exts
import GHC.Prim (isByteArrayWeaklyPinned#, isMutableByteArrayWeaklyPinned#)

{-# OPAQUE memoryCase #-}
memoryCase :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
memoryCase from to count value selected = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, bytes #) ->
  case mutableByteArrayContents# bytes of { base ->
  case setAddrRange# base 32# value s1 of { s2 ->
  case writeWord8Array# bytes 8# (wordToWord8# 99##) s2 of { s3 ->
  case copyAddrToAddr# (plusAddr# base from) (plusAddr# base to) count s3 of { s4 ->
  case readWord8Array# bytes selected s4 of { (# s5, answer #) ->
  case touch# bytes s5 of { _ -> word2Int# (word8ToWord# answer) }}}}}}})

{-# OPAQUE pinCase #-}
pinCase :: Int# -> Int#
pinCase mode = runRW# (\s0 ->
  case (case mode of { 0# -> newByteArray# 32# s0;
                      1# -> newPinnedByteArray# 32# s0;
                      _ -> newAlignedPinnedByteArray# 32# 64# s0 }) of { (# s1, bytes #) ->
  case isMutableByteArrayPinned# bytes of { strong ->
  case isMutableByteArrayWeaklyPinned# bytes of { weak ->
  case unsafeFreezeByteArray# bytes s1 of { (# _, frozen #) ->
    strong +# 2# *# weak +# 4# *# isByteArrayPinned# frozen +# 8# *# isByteArrayWeaklyPinned# frozen }}}})

{-# OPAQUE thawCase #-}
thawCase :: Int# -> Int#
thawCase value = runRW# (\s0 ->
  case newPinnedByteArray# 8# s0 of { (# s1, bytes #) ->
  case writeWord8Array# bytes 0# (wordToWord8# (int2Word# value)) s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of { (# s3, frozen #) ->
  case indexWord8Array# frozen 0# of { before ->
  case unsafeThawByteArray# frozen s3 of { (# s4, thawed #) ->
  case writeWord8Array# thawed 0# (wordToWord8# (int2Word# (value +# 1#))) s4 of { s5 ->
  case readWord8Array# thawed 0# s5 of { (# _, after #) ->
    word2Int# (word8ToWord# before) +# 256# *# word2Int# (word8ToWord# after) }}}}}}})

{-# OPAQUE shrinkCase #-}
shrinkCase :: Int# -> Int#
shrinkCase length = runRW# (\s0 ->
  case newSmallArray# 8# (I# 77#) s0 of { (# s1, array #) ->
  case shrinkSmallMutableArray# array length s1 of { s2 ->
  case getSizeofSmallMutableArray# array s2 of { (# s3, size #) ->
  case size of { 0# -> 0#; _ ->
    case readSmallArray# array (size -# 1#) s3 of { (# _, I# value #) -> size *# 100# +# value }}}}})

{-# OPAQUE differenceCase #-}
differenceCase :: Int# -> Int# -> Int#
differenceCase left right = runRW# (\s0 ->
  case newPinnedByteArray# 64# s0 of { (# s1, bytes #) ->
  case mutableByteArrayContents# bytes of { base ->
  case minusAddr# (plusAddr# base left) (plusAddr# base right) of { answer ->
  case touch# bytes s1 of { _ -> answer }}}})

{-# OPAQUE remainderCase #-}
remainderCase :: Int# -> Int# -> Int#
remainderCase offset divisor = runRW# (\s0 ->
  case newAlignedPinnedByteArray# 64# 64# s0 of { (# s1, bytes #) ->
  case remAddr# (plusAddr# (mutableByteArrayContents# bytes) offset) divisor of { answer ->
  case touch# bytes s1 of { _ -> answer }}})

{-# OPAQUE numericDifference #-}
numericDifference :: Int# -> Int# -> Int#
numericDifference left right = minusAddr# (int2Addr# left) (int2Addr# right)

{-# OPAQUE numericRemainder #-}
numericRemainder :: Int# -> Int# -> Int#
numericRemainder bits divisor = remAddr# (int2Addr# bits) divisor
