-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module AlignedScalarMemoryAudit where

import GHC.Exts

-- Aligned element indices use the same cell through Array and OffAddr.
-- Negative element indices from a one-past address are intentional.
-- Stable pointers stay opaque; no integer or byte conversion is used.

{-# OPAQUE alignedWideChar #-}
alignedWideChar :: Int# -> Int# -> Int# -> Int#
alignedWideChar raw offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 32# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 32# of { end ->
  case writeWideCharArray# mutable offset (chr# (andI# raw 1114111#)) s2 of { s3 ->
  case readWideCharOffAddr# end (offset -# 8#) s3 of { (# s4, first #) ->
  case readWideCharArray# mutable offset s4 of { (# s5, second #) ->
  case notI# raw of { next ->
  case writeWideCharOffAddr# end (offset -# 8#) (chr# (andI# next 1114111#)) s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> ord# first;
    1# -> ord# second;
    2# -> ord# (indexWideCharArray# bytes offset);
    3# -> ord# (indexWideCharOffAddr# end (offset -# 8#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset *# 4# +# 31#) 32#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset *# 4# +# 4#) 32#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } } })

{-# OPAQUE alignedStablePtr #-}
alignedStablePtr :: StablePtr# Int -> StablePtr# Int -> Int# -> Int# -> Int#
alignedStablePtr initial replacement offset selector = runRW# (\s0 ->
  case newPinnedByteArray# 64# s0 of { (# s1, mutable #) ->
  case setByteArray# mutable 0# 64# 165# s1 of { s2 ->
  case mutableByteArrayContents# mutable of { base ->
  case plusAddr# base 64# of { end ->
  case writeStablePtrArray# mutable offset initial s2 of { s3 ->
  case readStablePtrOffAddr# end (offset -# 8#) s3 of { (# s4, first #) ->
  case readStablePtrArray# mutable offset s4 of { (# s5, second #) ->
  case writeStablePtrOffAddr# end (offset -# 8#) replacement s5 of { s6 ->
  case unsafeFreezeByteArray# mutable s6 of { (# s7, bytes #) ->
  case (case selector of {
    0# -> eqStablePtr# initial first;
    1# -> eqStablePtr# initial second;
    2# -> eqStablePtr# replacement (indexStablePtrArray# bytes offset);
    3# -> eqStablePtr# replacement (indexStablePtrOffAddr# end (offset -# 8#));
    4# -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset *# 8# +# 63#) 64#)));
    _ -> word2Int# (word8ToWord# (indexWord8Array# bytes (remInt# (offset *# 8# +# 8#) 64#)))
  }) of { result ->
  case touch# mutable s7 of { _ -> result }
  } } } } } } } } } })
