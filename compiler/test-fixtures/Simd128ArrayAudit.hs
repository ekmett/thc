-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
-- Six public 128-bit integer vector shapes, packed and scalar-element offsets.
-- All bytes are initialized; the native oracle never probes out-of-bounds Core.
module Simd128ArrayAudit where
import GHC.Exts

{-# NOINLINE initialize #-}
initialize :: MutableByteArray# s -> Int# -> State# s -> State# s
initialize bytes seed s0 =
  case writeWord8Array# bytes 0# (wordToWord8# (int2Word# (seed +# 0#))) s0 of { s1 ->
  case writeWord8Array# bytes 1# (wordToWord8# (int2Word# (seed +# 37#))) s1 of { s2 ->
  case writeWord8Array# bytes 2# (wordToWord8# (int2Word# (seed +# 74#))) s2 of { s3 ->
  case writeWord8Array# bytes 3# (wordToWord8# (int2Word# (seed +# 111#))) s3 of { s4 ->
  case writeWord8Array# bytes 4# (wordToWord8# (int2Word# (seed +# 148#))) s4 of { s5 ->
  case writeWord8Array# bytes 5# (wordToWord8# (int2Word# (seed +# 185#))) s5 of { s6 ->
  case writeWord8Array# bytes 6# (wordToWord8# (int2Word# (seed +# 222#))) s6 of { s7 ->
  case writeWord8Array# bytes 7# (wordToWord8# (int2Word# (seed +# 259#))) s7 of { s8 ->
  case writeWord8Array# bytes 8# (wordToWord8# (int2Word# (seed +# 296#))) s8 of { s9 ->
  case writeWord8Array# bytes 9# (wordToWord8# (int2Word# (seed +# 333#))) s9 of { s10 ->
  case writeWord8Array# bytes 10# (wordToWord8# (int2Word# (seed +# 370#))) s10 of { s11 ->
  case writeWord8Array# bytes 11# (wordToWord8# (int2Word# (seed +# 407#))) s11 of { s12 ->
  case writeWord8Array# bytes 12# (wordToWord8# (int2Word# (seed +# 444#))) s12 of { s13 ->
  case writeWord8Array# bytes 13# (wordToWord8# (int2Word# (seed +# 481#))) s13 of { s14 ->
  case writeWord8Array# bytes 14# (wordToWord8# (int2Word# (seed +# 518#))) s14 of { s15 ->
  case writeWord8Array# bytes 15# (wordToWord8# (int2Word# (seed +# 555#))) s15 of { s16 ->
  case writeWord8Array# bytes 16# (wordToWord8# (int2Word# (seed +# 592#))) s16 of { s17 ->
  case writeWord8Array# bytes 17# (wordToWord8# (int2Word# (seed +# 629#))) s17 of { s18 ->
  case writeWord8Array# bytes 18# (wordToWord8# (int2Word# (seed +# 666#))) s18 of { s19 ->
  case writeWord8Array# bytes 19# (wordToWord8# (int2Word# (seed +# 703#))) s19 of { s20 ->
  case writeWord8Array# bytes 20# (wordToWord8# (int2Word# (seed +# 740#))) s20 of { s21 ->
  case writeWord8Array# bytes 21# (wordToWord8# (int2Word# (seed +# 777#))) s21 of { s22 ->
  case writeWord8Array# bytes 22# (wordToWord8# (int2Word# (seed +# 814#))) s22 of { s23 ->
  case writeWord8Array# bytes 23# (wordToWord8# (int2Word# (seed +# 851#))) s23 of { s24 ->
  case writeWord8Array# bytes 24# (wordToWord8# (int2Word# (seed +# 888#))) s24 of { s25 ->
  case writeWord8Array# bytes 25# (wordToWord8# (int2Word# (seed +# 925#))) s25 of { s26 ->
  case writeWord8Array# bytes 26# (wordToWord8# (int2Word# (seed +# 962#))) s26 of { s27 ->
  case writeWord8Array# bytes 27# (wordToWord8# (int2Word# (seed +# 999#))) s27 of { s28 ->
  case writeWord8Array# bytes 28# (wordToWord8# (int2Word# (seed +# 1036#))) s28 of { s29 ->
  case writeWord8Array# bytes 29# (wordToWord8# (int2Word# (seed +# 1073#))) s29 of { s30 ->
  case writeWord8Array# bytes 30# (wordToWord8# (int2Word# (seed +# 1110#))) s30 of { s31 ->
  case writeWord8Array# bytes 31# (wordToWord8# (int2Word# (seed +# 1147#))) s31 of { s32 ->
  case writeWord8Array# bytes 32# (wordToWord8# (int2Word# (seed +# 1184#))) s32 of { s33 ->
  case writeWord8Array# bytes 33# (wordToWord8# (int2Word# (seed +# 1221#))) s33 of { s34 ->
  case writeWord8Array# bytes 34# (wordToWord8# (int2Word# (seed +# 1258#))) s34 of { s35 ->
  case writeWord8Array# bytes 35# (wordToWord8# (int2Word# (seed +# 1295#))) s35 of { s36 ->
  case writeWord8Array# bytes 36# (wordToWord8# (int2Word# (seed +# 1332#))) s36 of { s37 ->
  case writeWord8Array# bytes 37# (wordToWord8# (int2Word# (seed +# 1369#))) s37 of { s38 ->
  case writeWord8Array# bytes 38# (wordToWord8# (int2Word# (seed +# 1406#))) s38 of { s39 ->
  case writeWord8Array# bytes 39# (wordToWord8# (int2Word# (seed +# 1443#))) s39 of { s40 ->
  case writeWord8Array# bytes 40# (wordToWord8# (int2Word# (seed +# 1480#))) s40 of { s41 ->
  case writeWord8Array# bytes 41# (wordToWord8# (int2Word# (seed +# 1517#))) s41 of { s42 ->
  case writeWord8Array# bytes 42# (wordToWord8# (int2Word# (seed +# 1554#))) s42 of { s43 ->
  case writeWord8Array# bytes 43# (wordToWord8# (int2Word# (seed +# 1591#))) s43 of { s44 ->
  case writeWord8Array# bytes 44# (wordToWord8# (int2Word# (seed +# 1628#))) s44 of { s45 ->
  case writeWord8Array# bytes 45# (wordToWord8# (int2Word# (seed +# 1665#))) s45 of { s46 ->
  case writeWord8Array# bytes 46# (wordToWord8# (int2Word# (seed +# 1702#))) s46 of { s47 ->
  case writeWord8Array# bytes 47# (wordToWord8# (int2Word# (seed +# 1739#))) s47 of { s48 ->
  s48 } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } } }

{-# NOINLINE checksum #-}
checksum :: ByteArray# -> Int#
checksum bytes = (word2Int# (word8ToWord# (indexWord8Array# bytes 0#)) *# 1#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 1#)) *# 3#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 2#)) *# 5#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 3#)) *# 7#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 4#)) *# 9#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 5#)) *# 11#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 6#)) *# 13#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 7#)) *# 15#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 8#)) *# 17#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 9#)) *# 19#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 10#)) *# 21#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 11#)) *# 23#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 12#)) *# 25#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 13#)) *# 27#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 14#)) *# 29#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 15#)) *# 31#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 16#)) *# 33#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 17#)) *# 35#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 18#)) *# 37#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 19#)) *# 39#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 20#)) *# 41#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 21#)) *# 43#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 22#)) *# 45#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 23#)) *# 47#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 24#)) *# 49#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 25#)) *# 51#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 26#)) *# 53#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 27#)) *# 55#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 28#)) *# 57#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 29#)) *# 59#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 30#)) *# 61#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 31#)) *# 63#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 32#)) *# 65#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 33#)) *# 67#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 34#)) *# 69#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 35#)) *# 71#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 36#)) *# 73#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 37#)) *# 75#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 38#)) *# 77#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 39#)) *# 79#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 40#)) *# 81#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 41#)) *# 83#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 42#)) *# 85#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 43#)) *# 87#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 44#)) *# 89#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 45#)) *# 91#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 46#)) *# 93#) +# (word2Int# (word8ToWord# (indexWord8Array# bytes 47#)) *# 95#)

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
