-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ManagedAddressReadAudit where

import GHC.Exts

-- Native domains use aligned addresses within a 32-byte live allocation.
-- Read again after a byte mutation to detect stale loads from frozen aliases.

word32Read :: Int# -> Int# -> Int# -> Int#
word32Read raw baseOffset elementOffset = runRW# (\s0 ->
  case newAlignedPinnedByteArray# 32# 8# s0 of { (# s1, mutable #) ->
  case writeWordArray# mutable 0# (int2Word# raw) s1 of { s2 ->
  case writeWordArray# mutable 1# (xor# (int2Word# raw) 81985529216486895##) s2 of { s3 ->
  case writeWordArray# mutable 2# (xor# (int2Word# raw) 18446744073709551615##) s3 of { s4 ->
  case writeWordArray# mutable 3# (xor# (int2Word# raw) 12297829382473034410##) s4 of { s5 ->
  case unsafeFreezeByteArray# mutable s5 of { (# s6, bytes #) ->
  case plusAddr# (byteArrayContents# bytes) baseOffset of { address ->
  case keepAlive# bytes s6 (\s ->
    case readWord32OffAddr# address elementOffset s of { (# t, before #) ->
    case writeWord8OffAddr# address (elementOffset *# 4#)
           (wordToWord8# (int2Word# (raw +# 173#))) t of { u ->
    case readWord32OffAddr# address elementOffset u of { (# v, after #) ->
      (# v, I# ((word2Int# (word32ToWord# before)) *# 3# +# (word2Int# (word32ToWord# after)) *# 5#) #)
    } } }) of { (# _, I# result #) -> result }
  } } } } } } })

wordRead :: Int# -> Int# -> Int# -> Int#
wordRead raw baseOffset elementOffset = runRW# (\s0 ->
  case newAlignedPinnedByteArray# 32# 8# s0 of { (# s1, mutable #) ->
  case writeWordArray# mutable 0# (int2Word# raw) s1 of { s2 ->
  case writeWordArray# mutable 1# (xor# (int2Word# raw) 81985529216486895##) s2 of { s3 ->
  case writeWordArray# mutable 2# (xor# (int2Word# raw) 18446744073709551615##) s3 of { s4 ->
  case writeWordArray# mutable 3# (xor# (int2Word# raw) 12297829382473034410##) s4 of { s5 ->
  case unsafeFreezeByteArray# mutable s5 of { (# s6, bytes #) ->
  case plusAddr# (byteArrayContents# bytes) baseOffset of { address ->
  case keepAlive# bytes s6 (\s ->
    case readWordOffAddr# address elementOffset s of { (# t, before #) ->
    case writeWord8OffAddr# address (elementOffset *# 8#)
           (wordToWord8# (int2Word# (raw +# 173#))) t of { u ->
    case readWordOffAddr# address elementOffset u of { (# v, after #) ->
      (# v, I# ((word2Int# before) *# 3# +# (word2Int# after) *# 5#) #)
    } } }) of { (# _, I# result #) -> result }
  } } } } } } })

int32Read :: Int# -> Int# -> Int# -> Int#
int32Read raw baseOffset elementOffset = runRW# (\s0 ->
  case newAlignedPinnedByteArray# 32# 8# s0 of { (# s1, mutable #) ->
  case writeWordArray# mutable 0# (int2Word# raw) s1 of { s2 ->
  case writeWordArray# mutable 1# (xor# (int2Word# raw) 81985529216486895##) s2 of { s3 ->
  case writeWordArray# mutable 2# (xor# (int2Word# raw) 18446744073709551615##) s3 of { s4 ->
  case writeWordArray# mutable 3# (xor# (int2Word# raw) 12297829382473034410##) s4 of { s5 ->
  case unsafeFreezeByteArray# mutable s5 of { (# s6, bytes #) ->
  case plusAddr# (byteArrayContents# bytes) baseOffset of { address ->
  case keepAlive# bytes s6 (\s ->
    case readInt32OffAddr# address elementOffset s of { (# t, before #) ->
    case writeWord8OffAddr# address (elementOffset *# 4#)
           (wordToWord8# (int2Word# (raw +# 173#))) t of { u ->
    case readInt32OffAddr# address elementOffset u of { (# v, after #) ->
      (# v, I# ((int32ToInt# before) *# 3# +# (int32ToInt# after) *# 5#) #)
    } } }) of { (# _, I# result #) -> result }
  } } } } } } })

intRead :: Int# -> Int# -> Int# -> Int#
intRead raw baseOffset elementOffset = runRW# (\s0 ->
  case newAlignedPinnedByteArray# 32# 8# s0 of { (# s1, mutable #) ->
  case writeWordArray# mutable 0# (int2Word# raw) s1 of { s2 ->
  case writeWordArray# mutable 1# (xor# (int2Word# raw) 81985529216486895##) s2 of { s3 ->
  case writeWordArray# mutable 2# (xor# (int2Word# raw) 18446744073709551615##) s3 of { s4 ->
  case writeWordArray# mutable 3# (xor# (int2Word# raw) 12297829382473034410##) s4 of { s5 ->
  case unsafeFreezeByteArray# mutable s5 of { (# s6, bytes #) ->
  case plusAddr# (byteArrayContents# bytes) baseOffset of { address ->
  case keepAlive# bytes s6 (\s ->
    case readIntOffAddr# address elementOffset s of { (# t, before #) ->
    case writeWord8OffAddr# address (elementOffset *# 8#)
           (wordToWord8# (int2Word# (raw +# 173#))) t of { u ->
    case readIntOffAddr# address elementOffset u of { (# v, after #) ->
      (# v, I# ((before) *# 3# +# (after) *# 5#) #)
    } } }) of { (# _, I# result #) -> result }
  } } } } } } })
