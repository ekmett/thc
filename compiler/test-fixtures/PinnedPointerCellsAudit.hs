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

-- A separate compact root keeps each Addr# array access in the compiled
-- guest path without making the original keepAlive# proof graph enormous.
{-# OPAQUE pointerArrayRoundtrip #-}
pointerArrayRoundtrip :: Int# -> Int#
pointerArrayRoundtrip raw = runRW# (\s0 ->
  case newPinnedByteArray# 16# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case plusAddr# base (8# +# andI# raw 7#) of { target ->
    case writeAddrArray# mutable 0# target s3 of { s4 ->
    case readAddrArray# mutable 0# s4 of { (# s5, loaded #) ->
    case indexAddrOffAddr# base 0# of { indexedOff ->
    case indexAddrArray# bytes 0# of { indexedArray ->
      (# s5, I# (eqAddr# loaded target
                  *# eqAddr# indexedOff target
                  *# eqAddr# indexedArray target) #)
    } } } } }) of { (# _, I# answer #) -> answer }
  } } })

-- Pointer ordering is defined here only among offsets of this one pinned
-- allocation (including one-past) and for null compared with itself.
{-# OPAQUE pointerOrder #-}
pointerOrder :: Int# -> Int#
pointerOrder raw = runRW# (\s0 ->
  case newPinnedByteArray# 16# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case plusAddr# base (andI# raw 7#) of { middle ->
      (# s3, I# (ltAddr# base middle
              +# leAddr# base middle *# 2#
              +# gtAddr# middle base *# 4#
              +# geAddr# middle base *# 8#
              +# ltAddr# base (plusAddr# base 16#) *# 16#
              +# leAddr# nullAddr# nullAddr# *# 32#
              +# geAddr# nullAddr# nullAddr# *# 64#) #)
    }) of { (# _, I# answer #) -> answer }
  } } })

-- Char# byte accesses use WordRep at the Core boundary, but store one byte.
-- Pack four independent observations so the native oracle checks both
-- address and array access without a per-operation compiler target.
{-# OPAQUE char8Roundtrip #-}
char8Roundtrip :: Int# -> Int#
char8Roundtrip raw = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, mutable #) ->
  case chr# (andI# raw 511#) of { character ->
  case writeCharArray# mutable 16# character s1 of { s2 ->
  case readCharArray# mutable 16# s2 of { (# s3, arrayRead #) ->
  case unsafeFreezeByteArray# mutable s3 of { (# s4, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s4 (\s5 ->
    case writeCharOffAddr# base 24# character s5 of { s6 ->
    case readCharOffAddr# base 24# s6 of { (# s7, addressRead #) ->
    case indexCharOffAddr# base 24# of { addressIndex ->
    case indexCharArray# bytes 16# of { arrayIndex ->
      (# s7, I# (ord# addressRead *# 16777216#
                +# ord# addressIndex *# 65536#
                +# ord# arrayRead *# 256#
                +# ord# arrayIndex) #)
    } } } }) of { (# _, I# answer #) -> answer }
  } } } } } })

-- A single pinned byte compares signed and unsigned pure/effectful reads.
-- Add 128 to each signed lane before packing to keep four exact 8-bit fields.
{-# OPAQUE byte8Roundtrip #-}
byte8Roundtrip :: Int# -> Int#
byte8Roundtrip raw = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case writeInt8OffAddr# base 24# (intToInt8# raw) s3 of { s4 ->
    case readInt8OffAddr# base 24# s4 of { (# s5, signedRead #) ->
    case readWord8OffAddr# base 24# s5 of { (# s6, unsignedRead #) ->
    case indexInt8OffAddr# base 24# of { signedIndex ->
    case indexWord8OffAddr# base 24# of { unsignedIndex ->
      (# s6, I# ((int8ToInt# signedRead +# 128#) *# 16777216#
                +# (int8ToInt# signedIndex +# 128#) *# 65536#
                +# word2Int# (word8ToWord# unsignedIndex) *# 256#
                +# word2Int# (word8ToWord# unsignedRead)) #)
    } } } } }) of { (# _, I# answer #) -> answer }
  } } })

-- The pinned array write supplies target-native byte order. The four Addr#
-- reads use two-byte element offsets and preserve signed/unsigned lanes.
{-# OPAQUE halfwordReadRoundtrip #-}
halfwordReadRoundtrip :: Int# -> Int#
halfwordReadRoundtrip raw = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, mutable #) ->
  case writeInt16Array# mutable 12# (intToInt16# raw) s1 of { s2 ->
  case unsafeFreezeByteArray# mutable s2 of { (# s3, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s3 (\s4 ->
    case readInt16OffAddr# base 12# s4 of { (# s5, signedRead #) ->
    case readWord16OffAddr# base 12# s5 of { (# s6, unsignedRead #) ->
    case indexInt16OffAddr# base 12# of { signedIndex ->
    case indexWord16OffAddr# base 12# of { unsignedIndex ->
      (# s6, I# ((int16ToInt# signedRead +# 32768#) *# 281474976710656#
                +# (int16ToInt# signedIndex +# 32768#) *# 4294967296#
                +# word2Int# (word16ToWord# unsignedRead) *# 65536#
                +# word2Int# (word16ToWord# unsignedIndex)) #)
    } } } }) of { (# _, I# answer #) -> answer }
  } } } })

-- Two native-endian halfword stores occupy bytes 16..17 and 24..25. A
-- retained address at bytes 8..15 proves both writes are disjoint from the
-- managed pointer cell; byte observations make the layout independently visible.
{-# OPAQUE halfwordWriteRoundtrip #-}
halfwordWriteRoundtrip :: Int# -> Int#
halfwordWriteRoundtrip raw = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case writeAddrOffAddr# base 1# (plusAddr# base 24#) s3 of { s4 ->
    case writeInt16OffAddr# base 12# (intToInt16# raw) s4 of { s5 ->
    case writeWord16OffAddr# base 8# (wordToWord16# (int2Word# (raw +# 32768#))) s5 of { s6 ->
    case readAddrOffAddr# base 1# s6 of { (# s7, target #) ->
    case indexWord8Array# bytes 16# of { a ->
    case indexWord8Array# bytes 17# of { b ->
    case indexWord8Array# bytes 24# of { c ->
    case indexWord8Array# bytes 25# of { d ->
      (# s7, I# (eqAddr# target (plusAddr# base 24#) *# 4294967296#
        +# word2Int# (word8ToWord# a) *# 16777216#
        +# word2Int# (word8ToWord# b) *# 65536#
        +# word2Int# (word8ToWord# c) *# 256#
        +# word2Int# (word8ToWord# d)) #)
    } } } } } } } }) of { (# _, I# answer #) -> answer }
  } } })

-- Six scalar stores fill bytes 16..55. A live pointer at 8..15 and a
-- byte-by-byte selector expose native layout and reject overlapping writes.
{-# OPAQUE wideStoreByte #-}
wideStoreByte :: Int# -> Int# -> Int#
wideStoreByte raw selector = runRW# (\s0 ->
  case newPinnedByteArray# 64# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case writeAddrOffAddr# base 1# (plusAddr# base 56#) s3 of { s4 ->
    case writeInt32OffAddr# base 4# (intToInt32# raw) s4 of { s5 ->
    case writeWord32OffAddr# base 5# (wordToWord32# (int2Word# (raw +# 17#))) s5 of { s6 ->
    case writeIntOffAddr# base 3# raw s6 of { s7 ->
    case writeWordOffAddr# base 4# (int2Word# (raw +# 33#)) s7 of { s8 ->
    case writeInt64OffAddr# base 5# (intToInt64# raw) s8 of { s9 ->
    case writeWord64OffAddr# base 6# (wordToWord64# (int2Word# (raw +# 49#))) s9 of { s10 ->
    case readAddrOffAddr# base 1# s10 of { (# s11, target #) ->
    case indexWord8Array# bytes (16# +# selector) of { byte ->
      (# s11, I# (case eqAddr# target (plusAddr# base 56#) of {
        1# -> word2Int# (word8ToWord# byte); _ -> -1# }) #)
    } } } } } } } } }) of { (# _, I# answer #) -> answer }
  } } })

-- Pure indexes and state-threaded 64-bit reads share one pinned image. All
-- index displacements are relative to base+24, so the 32-bit accesses use
-- negative element offsets while the 64-bit accesses use positive offsets.
{-# OPAQUE wideReadSelector #-}
wideReadSelector :: Int# -> Int# -> Int#
wideReadSelector raw selector = runRW# (\s0 ->
  case newPinnedByteArray# 64# s0 of { (# s1, mutable #) ->
  case unsafeFreezeByteArray# mutable s1 of { (# s2, bytes #) ->
  case byteArrayContents# bytes of { base ->
  case keepAlive# bytes s2 (\s3 ->
    case writeInt32OffAddr# base 4# (intToInt32# raw) s3 of { s4 ->
    case writeWord32OffAddr# base 5# (wordToWord32# (int2Word# (raw +# 17#))) s4 of { s5 ->
    case writeIntOffAddr# base 3# raw s5 of { s6 ->
    case writeWordOffAddr# base 4# (int2Word# (raw +# 33#)) s6 of { s7 ->
    case writeInt64OffAddr# base 5# (intToInt64# raw) s7 of { s8 ->
    case writeWord64OffAddr# base 6# (wordToWord64# (int2Word# (raw +# 49#))) s8 of { s9 ->
    case plusAddr# base 24# of { origin ->
    case selector of {
      0# -> (# s9, I# (int32ToInt# (indexInt32OffAddr# origin (-2#))) #);
      1# -> (# s9, I# (word2Int# (word32ToWord# (indexWord32OffAddr# origin (-1#)))) #);
      2# -> (# s9, I# (indexIntOffAddr# origin 0#) #);
      3# -> (# s9, I# (word2Int# (indexWordOffAddr# origin 1#)) #);
      4# -> (# s9, I# (int64ToInt# (indexInt64OffAddr# origin 2#)) #);
      5# -> (# s9, I# (word2Int# (word64ToWord# (indexWord64OffAddr# origin 3#))) #);
      6# -> case readInt64OffAddr# origin 2# s9 of { (# s10, value #) ->
              (# s10, I# (int64ToInt# value) #) };
      _  -> case readWord64OffAddr# origin 3# s9 of { (# s10, value #) ->
              (# s10, I# (word2Int# (word64ToWord# value)) #) }
    } } } } } } } }) of { (# _, I# answer #) -> answer }
  } } })
