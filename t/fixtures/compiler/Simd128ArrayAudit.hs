-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : Simd128ArrayAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Six public 128-bit integer vector shapes, packed and scalar-element offsets.
-- All bytes are initialized; the native oracle never probes out-of-bounds Core.
module Simd128ArrayAudit where
import GHC.Exts

{-# OPAQUE initialize #-}
initialize :: MutableByteArray# s -> Int# -> State# s -> State# s
initialize bytes seed = go 0#
  where
    go index state = case index ==# 48# of
      1# -> state
      _ -> case writeWord8Array# bytes index
                  (wordToWord8# (int2Word# (seed +# index *# 37#))) state of
        next -> go (index +# 1#) next

{-# OPAQUE checksum #-}
checksum :: ByteArray# -> Int#
checksum bytes = go 0# 0#
  where
    go index total = case index ==# 48# of
      1# -> total
      _ -> go (index +# 1#)
        (total +# word2Int# (word8ToWord# (indexWord8Array# bytes index)) *# (2# *# index +# 1#))

{-# NOINLINE int8X16IndexPacked #-}
int8X16IndexPacked :: Int# -> Int# -> Int#
int8X16IndexPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackInt8X16# (indexInt8X16Array# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (int8ToInt# v0 *# 1#) +# (int8ToInt# v1 *# 3#) +# (int8ToInt# v2 *# 5#) +# (int8ToInt# v3 *# 7#) +# (int8ToInt# v4 *# 9#) +# (int8ToInt# v5 *# 11#) +# (int8ToInt# v6 *# 13#) +# (int8ToInt# v7 *# 15#) +# (int8ToInt# v8 *# 17#) +# (int8ToInt# v9 *# 19#) +# (int8ToInt# v10 *# 21#) +# (int8ToInt# v11 *# 23#) +# (int8ToInt# v12 *# 25#) +# (int8ToInt# v13 *# 27#) +# (int8ToInt# v14 *# 29#) +# (int8ToInt# v15 *# 31#)
  }})

{-# NOINLINE int8X16IndexScalar #-}
int8X16IndexScalar :: Int# -> Int# -> Int#
int8X16IndexScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackInt8X16# (indexInt8ArrayAsInt8X16# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (int8ToInt# v0 *# 1#) +# (int8ToInt# v1 *# 3#) +# (int8ToInt# v2 *# 5#) +# (int8ToInt# v3 *# 7#) +# (int8ToInt# v4 *# 9#) +# (int8ToInt# v5 *# 11#) +# (int8ToInt# v6 *# 13#) +# (int8ToInt# v7 *# 15#) +# (int8ToInt# v8 *# 17#) +# (int8ToInt# v9 *# 19#) +# (int8ToInt# v10 *# 21#) +# (int8ToInt# v11 *# 23#) +# (int8ToInt# v12 *# 25#) +# (int8ToInt# v13 *# 27#) +# (int8ToInt# v14 *# 29#) +# (int8ToInt# v15 *# 31#)
  }})

{-# NOINLINE int8X16ReadPacked #-}
int8X16ReadPacked :: Int# -> Int# -> Int#
int8X16ReadPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readInt8X16Array# bytes offset s2 of
    (# _, vector #) ->
      case unpackInt8X16# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (int8ToInt# v0 *# 1#) +# (int8ToInt# v1 *# 3#) +# (int8ToInt# v2 *# 5#) +# (int8ToInt# v3 *# 7#) +# (int8ToInt# v4 *# 9#) +# (int8ToInt# v5 *# 11#) +# (int8ToInt# v6 *# 13#) +# (int8ToInt# v7 *# 15#) +# (int8ToInt# v8 *# 17#) +# (int8ToInt# v9 *# 19#) +# (int8ToInt# v10 *# 21#) +# (int8ToInt# v11 *# 23#) +# (int8ToInt# v12 *# 25#) +# (int8ToInt# v13 *# 27#) +# (int8ToInt# v14 *# 29#) +# (int8ToInt# v15 *# 31#)
  }})

{-# NOINLINE int8X16ReadScalar #-}
int8X16ReadScalar :: Int# -> Int# -> Int#
int8X16ReadScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readInt8ArrayAsInt8X16# bytes offset s2 of
    (# _, vector #) ->
      case unpackInt8X16# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (int8ToInt# v0 *# 1#) +# (int8ToInt# v1 *# 3#) +# (int8ToInt# v2 *# 5#) +# (int8ToInt# v3 *# 7#) +# (int8ToInt# v4 *# 9#) +# (int8ToInt# v5 *# 11#) +# (int8ToInt# v6 *# 13#) +# (int8ToInt# v7 *# 15#) +# (int8ToInt# v8 *# 17#) +# (int8ToInt# v9 *# 19#) +# (int8ToInt# v10 *# 21#) +# (int8ToInt# v11 *# 23#) +# (int8ToInt# v12 *# 25#) +# (int8ToInt# v13 *# 27#) +# (int8ToInt# v14 *# 29#) +# (int8ToInt# v15 *# 31#)
  }})

{-# NOINLINE int8X16WritePacked #-}
int8X16WritePacked :: Int# -> Int# -> Int#
int8X16WritePacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeInt8X16Array# bytes offset (packInt8X16# (# intToInt8# (seed *# 23# +# 0#), intToInt8# (seed *# 23# +# 97#), intToInt8# (seed *# 23# +# 194#), intToInt8# (seed *# 23# +# 291#), intToInt8# (seed *# 23# +# 388#), intToInt8# (seed *# 23# +# 485#), intToInt8# (seed *# 23# +# 582#), intToInt8# (seed *# 23# +# 679#), intToInt8# (seed *# 23# +# 776#), intToInt8# (seed *# 23# +# 873#), intToInt8# (seed *# 23# +# 970#), intToInt8# (seed *# 23# +# 1067#), intToInt8# (seed *# 23# +# 1164#), intToInt8# (seed *# 23# +# 1261#), intToInt8# (seed *# 23# +# 1358#), intToInt8# (seed *# 23# +# 1455#) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE int8X16WriteScalar #-}
int8X16WriteScalar :: Int# -> Int# -> Int#
int8X16WriteScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeInt8ArrayAsInt8X16# bytes offset (packInt8X16# (# intToInt8# (seed *# 23# +# 0#), intToInt8# (seed *# 23# +# 97#), intToInt8# (seed *# 23# +# 194#), intToInt8# (seed *# 23# +# 291#), intToInt8# (seed *# 23# +# 388#), intToInt8# (seed *# 23# +# 485#), intToInt8# (seed *# 23# +# 582#), intToInt8# (seed *# 23# +# 679#), intToInt8# (seed *# 23# +# 776#), intToInt8# (seed *# 23# +# 873#), intToInt8# (seed *# 23# +# 970#), intToInt8# (seed *# 23# +# 1067#), intToInt8# (seed *# 23# +# 1164#), intToInt8# (seed *# 23# +# 1261#), intToInt8# (seed *# 23# +# 1358#), intToInt8# (seed *# 23# +# 1455#) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE word8X16IndexPacked #-}
word8X16IndexPacked :: Int# -> Int# -> Int#
word8X16IndexPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackWord8X16# (indexWord8X16Array# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (word2Int# (word8ToWord# v0) *# 1#) +# (word2Int# (word8ToWord# v1) *# 3#) +# (word2Int# (word8ToWord# v2) *# 5#) +# (word2Int# (word8ToWord# v3) *# 7#) +# (word2Int# (word8ToWord# v4) *# 9#) +# (word2Int# (word8ToWord# v5) *# 11#) +# (word2Int# (word8ToWord# v6) *# 13#) +# (word2Int# (word8ToWord# v7) *# 15#) +# (word2Int# (word8ToWord# v8) *# 17#) +# (word2Int# (word8ToWord# v9) *# 19#) +# (word2Int# (word8ToWord# v10) *# 21#) +# (word2Int# (word8ToWord# v11) *# 23#) +# (word2Int# (word8ToWord# v12) *# 25#) +# (word2Int# (word8ToWord# v13) *# 27#) +# (word2Int# (word8ToWord# v14) *# 29#) +# (word2Int# (word8ToWord# v15) *# 31#)
  }})

{-# NOINLINE word8X16IndexScalar #-}
word8X16IndexScalar :: Int# -> Int# -> Int#
word8X16IndexScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackWord8X16# (indexWord8ArrayAsWord8X16# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (word2Int# (word8ToWord# v0) *# 1#) +# (word2Int# (word8ToWord# v1) *# 3#) +# (word2Int# (word8ToWord# v2) *# 5#) +# (word2Int# (word8ToWord# v3) *# 7#) +# (word2Int# (word8ToWord# v4) *# 9#) +# (word2Int# (word8ToWord# v5) *# 11#) +# (word2Int# (word8ToWord# v6) *# 13#) +# (word2Int# (word8ToWord# v7) *# 15#) +# (word2Int# (word8ToWord# v8) *# 17#) +# (word2Int# (word8ToWord# v9) *# 19#) +# (word2Int# (word8ToWord# v10) *# 21#) +# (word2Int# (word8ToWord# v11) *# 23#) +# (word2Int# (word8ToWord# v12) *# 25#) +# (word2Int# (word8ToWord# v13) *# 27#) +# (word2Int# (word8ToWord# v14) *# 29#) +# (word2Int# (word8ToWord# v15) *# 31#)
  }})

{-# NOINLINE word8X16ReadPacked #-}
word8X16ReadPacked :: Int# -> Int# -> Int#
word8X16ReadPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readWord8X16Array# bytes offset s2 of
    (# _, vector #) ->
      case unpackWord8X16# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (word2Int# (word8ToWord# v0) *# 1#) +# (word2Int# (word8ToWord# v1) *# 3#) +# (word2Int# (word8ToWord# v2) *# 5#) +# (word2Int# (word8ToWord# v3) *# 7#) +# (word2Int# (word8ToWord# v4) *# 9#) +# (word2Int# (word8ToWord# v5) *# 11#) +# (word2Int# (word8ToWord# v6) *# 13#) +# (word2Int# (word8ToWord# v7) *# 15#) +# (word2Int# (word8ToWord# v8) *# 17#) +# (word2Int# (word8ToWord# v9) *# 19#) +# (word2Int# (word8ToWord# v10) *# 21#) +# (word2Int# (word8ToWord# v11) *# 23#) +# (word2Int# (word8ToWord# v12) *# 25#) +# (word2Int# (word8ToWord# v13) *# 27#) +# (word2Int# (word8ToWord# v14) *# 29#) +# (word2Int# (word8ToWord# v15) *# 31#)
  }})

{-# NOINLINE word8X16ReadScalar #-}
word8X16ReadScalar :: Int# -> Int# -> Int#
word8X16ReadScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readWord8ArrayAsWord8X16# bytes offset s2 of
    (# _, vector #) ->
      case unpackWord8X16# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
          (word2Int# (word8ToWord# v0) *# 1#) +# (word2Int# (word8ToWord# v1) *# 3#) +# (word2Int# (word8ToWord# v2) *# 5#) +# (word2Int# (word8ToWord# v3) *# 7#) +# (word2Int# (word8ToWord# v4) *# 9#) +# (word2Int# (word8ToWord# v5) *# 11#) +# (word2Int# (word8ToWord# v6) *# 13#) +# (word2Int# (word8ToWord# v7) *# 15#) +# (word2Int# (word8ToWord# v8) *# 17#) +# (word2Int# (word8ToWord# v9) *# 19#) +# (word2Int# (word8ToWord# v10) *# 21#) +# (word2Int# (word8ToWord# v11) *# 23#) +# (word2Int# (word8ToWord# v12) *# 25#) +# (word2Int# (word8ToWord# v13) *# 27#) +# (word2Int# (word8ToWord# v14) *# 29#) +# (word2Int# (word8ToWord# v15) *# 31#)
  }})

{-# NOINLINE word8X16WritePacked #-}
word8X16WritePacked :: Int# -> Int# -> Int#
word8X16WritePacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeWord8X16Array# bytes offset (packWord8X16# (# wordToWord8# (int2Word# (seed *# 23# +# 0#)), wordToWord8# (int2Word# (seed *# 23# +# 97#)), wordToWord8# (int2Word# (seed *# 23# +# 194#)), wordToWord8# (int2Word# (seed *# 23# +# 291#)), wordToWord8# (int2Word# (seed *# 23# +# 388#)), wordToWord8# (int2Word# (seed *# 23# +# 485#)), wordToWord8# (int2Word# (seed *# 23# +# 582#)), wordToWord8# (int2Word# (seed *# 23# +# 679#)), wordToWord8# (int2Word# (seed *# 23# +# 776#)), wordToWord8# (int2Word# (seed *# 23# +# 873#)), wordToWord8# (int2Word# (seed *# 23# +# 970#)), wordToWord8# (int2Word# (seed *# 23# +# 1067#)), wordToWord8# (int2Word# (seed *# 23# +# 1164#)), wordToWord8# (int2Word# (seed *# 23# +# 1261#)), wordToWord8# (int2Word# (seed *# 23# +# 1358#)), wordToWord8# (int2Word# (seed *# 23# +# 1455#)) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE word8X16WriteScalar #-}
word8X16WriteScalar :: Int# -> Int# -> Int#
word8X16WriteScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeWord8ArrayAsWord8X16# bytes offset (packWord8X16# (# wordToWord8# (int2Word# (seed *# 23# +# 0#)), wordToWord8# (int2Word# (seed *# 23# +# 97#)), wordToWord8# (int2Word# (seed *# 23# +# 194#)), wordToWord8# (int2Word# (seed *# 23# +# 291#)), wordToWord8# (int2Word# (seed *# 23# +# 388#)), wordToWord8# (int2Word# (seed *# 23# +# 485#)), wordToWord8# (int2Word# (seed *# 23# +# 582#)), wordToWord8# (int2Word# (seed *# 23# +# 679#)), wordToWord8# (int2Word# (seed *# 23# +# 776#)), wordToWord8# (int2Word# (seed *# 23# +# 873#)), wordToWord8# (int2Word# (seed *# 23# +# 970#)), wordToWord8# (int2Word# (seed *# 23# +# 1067#)), wordToWord8# (int2Word# (seed *# 23# +# 1164#)), wordToWord8# (int2Word# (seed *# 23# +# 1261#)), wordToWord8# (int2Word# (seed *# 23# +# 1358#)), wordToWord8# (int2Word# (seed *# 23# +# 1455#)) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE int16X8IndexPacked #-}
int16X8IndexPacked :: Int# -> Int# -> Int#
int16X8IndexPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackInt16X8# (indexInt16X8Array# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (int16ToInt# v0 *# 1#) +# (int16ToInt# v1 *# 3#) +# (int16ToInt# v2 *# 5#) +# (int16ToInt# v3 *# 7#) +# (int16ToInt# v4 *# 9#) +# (int16ToInt# v5 *# 11#) +# (int16ToInt# v6 *# 13#) +# (int16ToInt# v7 *# 15#)
  }})

{-# NOINLINE int16X8IndexScalar #-}
int16X8IndexScalar :: Int# -> Int# -> Int#
int16X8IndexScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackInt16X8# (indexInt16ArrayAsInt16X8# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (int16ToInt# v0 *# 1#) +# (int16ToInt# v1 *# 3#) +# (int16ToInt# v2 *# 5#) +# (int16ToInt# v3 *# 7#) +# (int16ToInt# v4 *# 9#) +# (int16ToInt# v5 *# 11#) +# (int16ToInt# v6 *# 13#) +# (int16ToInt# v7 *# 15#)
  }})

{-# NOINLINE int16X8ReadPacked #-}
int16X8ReadPacked :: Int# -> Int# -> Int#
int16X8ReadPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readInt16X8Array# bytes offset s2 of
    (# _, vector #) ->
      case unpackInt16X8# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (int16ToInt# v0 *# 1#) +# (int16ToInt# v1 *# 3#) +# (int16ToInt# v2 *# 5#) +# (int16ToInt# v3 *# 7#) +# (int16ToInt# v4 *# 9#) +# (int16ToInt# v5 *# 11#) +# (int16ToInt# v6 *# 13#) +# (int16ToInt# v7 *# 15#)
  }})

{-# NOINLINE int16X8ReadScalar #-}
int16X8ReadScalar :: Int# -> Int# -> Int#
int16X8ReadScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readInt16ArrayAsInt16X8# bytes offset s2 of
    (# _, vector #) ->
      case unpackInt16X8# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (int16ToInt# v0 *# 1#) +# (int16ToInt# v1 *# 3#) +# (int16ToInt# v2 *# 5#) +# (int16ToInt# v3 *# 7#) +# (int16ToInt# v4 *# 9#) +# (int16ToInt# v5 *# 11#) +# (int16ToInt# v6 *# 13#) +# (int16ToInt# v7 *# 15#)
  }})

{-# NOINLINE int16X8WritePacked #-}
int16X8WritePacked :: Int# -> Int# -> Int#
int16X8WritePacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeInt16X8Array# bytes offset (packInt16X8# (# intToInt16# (seed *# 23# +# 0#), intToInt16# (seed *# 23# +# 97#), intToInt16# (seed *# 23# +# 194#), intToInt16# (seed *# 23# +# 291#), intToInt16# (seed *# 23# +# 388#), intToInt16# (seed *# 23# +# 485#), intToInt16# (seed *# 23# +# 582#), intToInt16# (seed *# 23# +# 679#) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE int16X8WriteScalar #-}
int16X8WriteScalar :: Int# -> Int# -> Int#
int16X8WriteScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeInt16ArrayAsInt16X8# bytes offset (packInt16X8# (# intToInt16# (seed *# 23# +# 0#), intToInt16# (seed *# 23# +# 97#), intToInt16# (seed *# 23# +# 194#), intToInt16# (seed *# 23# +# 291#), intToInt16# (seed *# 23# +# 388#), intToInt16# (seed *# 23# +# 485#), intToInt16# (seed *# 23# +# 582#), intToInt16# (seed *# 23# +# 679#) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE word16X8IndexPacked #-}
word16X8IndexPacked :: Int# -> Int# -> Int#
word16X8IndexPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackWord16X8# (indexWord16X8Array# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (word2Int# (word16ToWord# v0) *# 1#) +# (word2Int# (word16ToWord# v1) *# 3#) +# (word2Int# (word16ToWord# v2) *# 5#) +# (word2Int# (word16ToWord# v3) *# 7#) +# (word2Int# (word16ToWord# v4) *# 9#) +# (word2Int# (word16ToWord# v5) *# 11#) +# (word2Int# (word16ToWord# v6) *# 13#) +# (word2Int# (word16ToWord# v7) *# 15#)
  }})

{-# NOINLINE word16X8IndexScalar #-}
word16X8IndexScalar :: Int# -> Int# -> Int#
word16X8IndexScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackWord16X8# (indexWord16ArrayAsWord16X8# frozen offset) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (word2Int# (word16ToWord# v0) *# 1#) +# (word2Int# (word16ToWord# v1) *# 3#) +# (word2Int# (word16ToWord# v2) *# 5#) +# (word2Int# (word16ToWord# v3) *# 7#) +# (word2Int# (word16ToWord# v4) *# 9#) +# (word2Int# (word16ToWord# v5) *# 11#) +# (word2Int# (word16ToWord# v6) *# 13#) +# (word2Int# (word16ToWord# v7) *# 15#)
  }})

{-# NOINLINE word16X8ReadPacked #-}
word16X8ReadPacked :: Int# -> Int# -> Int#
word16X8ReadPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readWord16X8Array# bytes offset s2 of
    (# _, vector #) ->
      case unpackWord16X8# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (word2Int# (word16ToWord# v0) *# 1#) +# (word2Int# (word16ToWord# v1) *# 3#) +# (word2Int# (word16ToWord# v2) *# 5#) +# (word2Int# (word16ToWord# v3) *# 7#) +# (word2Int# (word16ToWord# v4) *# 9#) +# (word2Int# (word16ToWord# v5) *# 11#) +# (word2Int# (word16ToWord# v6) *# 13#) +# (word2Int# (word16ToWord# v7) *# 15#)
  }})

{-# NOINLINE word16X8ReadScalar #-}
word16X8ReadScalar :: Int# -> Int# -> Int#
word16X8ReadScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readWord16ArrayAsWord16X8# bytes offset s2 of
    (# _, vector #) ->
      case unpackWord16X8# (vector) of
        (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
          (word2Int# (word16ToWord# v0) *# 1#) +# (word2Int# (word16ToWord# v1) *# 3#) +# (word2Int# (word16ToWord# v2) *# 5#) +# (word2Int# (word16ToWord# v3) *# 7#) +# (word2Int# (word16ToWord# v4) *# 9#) +# (word2Int# (word16ToWord# v5) *# 11#) +# (word2Int# (word16ToWord# v6) *# 13#) +# (word2Int# (word16ToWord# v7) *# 15#)
  }})

{-# NOINLINE word16X8WritePacked #-}
word16X8WritePacked :: Int# -> Int# -> Int#
word16X8WritePacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeWord16X8Array# bytes offset (packWord16X8# (# wordToWord16# (int2Word# (seed *# 23# +# 0#)), wordToWord16# (int2Word# (seed *# 23# +# 97#)), wordToWord16# (int2Word# (seed *# 23# +# 194#)), wordToWord16# (int2Word# (seed *# 23# +# 291#)), wordToWord16# (int2Word# (seed *# 23# +# 388#)), wordToWord16# (int2Word# (seed *# 23# +# 485#)), wordToWord16# (int2Word# (seed *# 23# +# 582#)), wordToWord16# (int2Word# (seed *# 23# +# 679#)) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE word16X8WriteScalar #-}
word16X8WriteScalar :: Int# -> Int# -> Int#
word16X8WriteScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeWord16ArrayAsWord16X8# bytes offset (packWord16X8# (# wordToWord16# (int2Word# (seed *# 23# +# 0#)), wordToWord16# (int2Word# (seed *# 23# +# 97#)), wordToWord16# (int2Word# (seed *# 23# +# 194#)), wordToWord16# (int2Word# (seed *# 23# +# 291#)), wordToWord16# (int2Word# (seed *# 23# +# 388#)), wordToWord16# (int2Word# (seed *# 23# +# 485#)), wordToWord16# (int2Word# (seed *# 23# +# 582#)), wordToWord16# (int2Word# (seed *# 23# +# 679#)) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE int64X2IndexPacked #-}
int64X2IndexPacked :: Int# -> Int# -> Int#
int64X2IndexPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackInt64X2# (indexInt64X2Array# frozen offset) of
        (# v0, v1 #) ->
          (int64ToInt# v0 *# 1#) +# (int64ToInt# v1 *# 3#)
  }})

{-# NOINLINE int64X2IndexScalar #-}
int64X2IndexScalar :: Int# -> Int# -> Int#
int64X2IndexScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackInt64X2# (indexInt64ArrayAsInt64X2# frozen offset) of
        (# v0, v1 #) ->
          (int64ToInt# v0 *# 1#) +# (int64ToInt# v1 *# 3#)
  }})

{-# NOINLINE int64X2ReadPacked #-}
int64X2ReadPacked :: Int# -> Int# -> Int#
int64X2ReadPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readInt64X2Array# bytes offset s2 of
    (# _, vector #) ->
      case unpackInt64X2# (vector) of
        (# v0, v1 #) ->
          (int64ToInt# v0 *# 1#) +# (int64ToInt# v1 *# 3#)
  }})

{-# NOINLINE int64X2ReadScalar #-}
int64X2ReadScalar :: Int# -> Int# -> Int#
int64X2ReadScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readInt64ArrayAsInt64X2# bytes offset s2 of
    (# _, vector #) ->
      case unpackInt64X2# (vector) of
        (# v0, v1 #) ->
          (int64ToInt# v0 *# 1#) +# (int64ToInt# v1 *# 3#)
  }})

{-# NOINLINE int64X2WritePacked #-}
int64X2WritePacked :: Int# -> Int# -> Int#
int64X2WritePacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeInt64X2Array# bytes offset (packInt64X2# (# intToInt64# (seed *# 23# +# 0#), intToInt64# (seed *# 23# +# 97#) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE int64X2WriteScalar #-}
int64X2WriteScalar :: Int# -> Int# -> Int#
int64X2WriteScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeInt64ArrayAsInt64X2# bytes offset (packInt64X2# (# intToInt64# (seed *# 23# +# 0#), intToInt64# (seed *# 23# +# 97#) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE word64X2IndexPacked #-}
word64X2IndexPacked :: Int# -> Int# -> Int#
word64X2IndexPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackWord64X2# (indexWord64X2Array# frozen offset) of
        (# v0, v1 #) ->
          (word2Int# (word64ToWord# v0) *# 1#) +# (word2Int# (word64ToWord# v1) *# 3#)
  }})

{-# NOINLINE word64X2IndexScalar #-}
word64X2IndexScalar :: Int# -> Int# -> Int#
word64X2IndexScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case unsafeFreezeByteArray# bytes s2 of
    (# _, frozen #) ->
      case unpackWord64X2# (indexWord64ArrayAsWord64X2# frozen offset) of
        (# v0, v1 #) ->
          (word2Int# (word64ToWord# v0) *# 1#) +# (word2Int# (word64ToWord# v1) *# 3#)
  }})

{-# NOINLINE word64X2ReadPacked #-}
word64X2ReadPacked :: Int# -> Int# -> Int#
word64X2ReadPacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readWord64X2Array# bytes offset s2 of
    (# _, vector #) ->
      case unpackWord64X2# (vector) of
        (# v0, v1 #) ->
          (word2Int# (word64ToWord# v0) *# 1#) +# (word2Int# (word64ToWord# v1) *# 3#)
  }})

{-# NOINLINE word64X2ReadScalar #-}
word64X2ReadScalar :: Int# -> Int# -> Int#
word64X2ReadScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case readWord64ArrayAsWord64X2# bytes offset s2 of
    (# _, vector #) ->
      case unpackWord64X2# (vector) of
        (# v0, v1 #) ->
          (word2Int# (word64ToWord# v0) *# 1#) +# (word2Int# (word64ToWord# v1) *# 3#)
  }})

{-# NOINLINE word64X2WritePacked #-}
word64X2WritePacked :: Int# -> Int# -> Int#
word64X2WritePacked seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeWord64X2Array# bytes offset (packWord64X2# (# wordToWord64# (int2Word# (seed *# 23# +# 0#)), wordToWord64# (int2Word# (seed *# 23# +# 97#)) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})

{-# NOINLINE word64X2WriteScalar #-}
word64X2WriteScalar :: Int# -> Int# -> Int#
word64X2WriteScalar seed offset = runRW# (\s0 ->
  case newByteArray# 48# s0 of { (# s1, bytes #) ->
  case initialize bytes seed s1 of { s2 ->
  case writeWord64ArrayAsWord64X2# bytes offset (packWord64X2# (# wordToWord64# (int2Word# (seed *# 23# +# 0#)), wordToWord64# (int2Word# (seed *# 23# +# 97#)) #)) s2 of { s3 ->
  case unsafeFreezeByteArray# bytes s3 of
    (# _, frozen #) -> checksum frozen
  } }})
