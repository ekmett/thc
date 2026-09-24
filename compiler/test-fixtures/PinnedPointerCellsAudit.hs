-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module PinnedPointerCellsAudit where

import GHC.Exts

-- Keep the allocation alive while deriving, storing, and re-reading an
-- interior pointer. The pointer field occupies bytes 8..15 on this target;
-- the numeric field at byte 16 proves disjoint writes survive.
{-# OPAQUE pointerRoundtrip #-}
pointerRoundtrip :: Int# -> Int#
pointerRoundtrip raw = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case writeWord8OffAddr# base 24# (wordToWord8# (int2Word# raw)) s3 of { s4 ->
    case writeAddrOffAddr# base 1# (plusAddr# base 24#) s4 of { s5 ->
    case writeWord8OffAddr# base 16# (wordToWord8# 9##) s5 of { s6 ->
    case readAddrOffAddr# base 1# s6 of { (# s7, target #) ->
    case readWord8OffAddr# target 0# s7 of { (# s8, observed #) ->
    case indexWord8Array# bytes 16# of { separate ->
      (# s8, I# (eqAddr# target (plusAddr# base 24#) *# 1000#
        +# word2Int# (word8ToWord# observed) *# 17#
        +# word2Int# (word8ToWord# separate)) #)
    } } } } } }) of { (# _, I# answer #) -> answer }
  } } })
