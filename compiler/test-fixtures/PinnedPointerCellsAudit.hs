-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module PinnedPointerCellsAudit where

import GHC.Exts

-- Keep the genuine mutable contents primop reachable, without freezing or
-- copying the allocation. The helper also exposes its checked managed offset
-- boundary to the JVM tests; native calls use only in-bounds offsets.
{-# OPAQUE mutableContentsAt #-}
mutableContentsAt :: MutableByteArray# s -> Int# -> Addr#
mutableContentsAt bytes offset = plusAddr# (mutableByteArrayContents# bytes) offset

{-# OPAQUE mutableContentsRoundtrip #-}
mutableContentsRoundtrip :: Int# -> Int#
mutableContentsRoundtrip raw = runRW# (\s0 ->
  case newPinnedByteArray# 32# s0 of { (# s1, bytes #) ->
  case andI# raw 15# of { offset ->
  case mutableContentsAt bytes 0# of { base ->
  case mutableContentsAt bytes offset of { interior ->
  case writeWord8Array# bytes offset (wordToWord8# (int2Word# raw)) s1 of { s2 ->
  case readWord8OffAddr# interior 0# s2 of { (# s3, fromArray #) ->
  case writeWord8OffAddr# interior 0# (wordToWord8# (int2Word# (xorI# raw 90#))) s3 of { s4 ->
  case readWord8Array# bytes offset s4 of { (# s5, fromAddress #) ->
  case touch# bytes s5 of { _ ->
    eqAddr# interior (plusAddr# base offset) *# 1000000#
      +# word2Int# (word8ToWord# fromArray) *# 256#
      +# word2Int# (word8ToWord# fromAddress)
  } } } } } } } } })

-- A lifted bottom must remain unevaluated by touch#. A wrong strictness
-- implementation raises immediately instead of hanging the native oracle.
{-# OPAQUE opaqueBottom #-}
opaqueBottom :: Int# -> Int
opaqueBottom raw = raise# (I# raw)

{-# OPAQUE touchLazyPayload #-}
touchLazyPayload :: Int# -> Int#
touchLazyPayload raw = runRW# (\s ->
  case touch# (opaqueBottom raw) s of { _ -> raw +# 37# })

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
