-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdWideArrayAudit where
import GHC.Exts

{-# NOINLINE int16X16IndexPacked #-}
int16X16IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int16X16IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt16X16Array# frozen offset of { vector -> case unpackInt16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int16ToInt# v0) *# 1#) +# ((int16ToInt# v1) *# 3#)) +# ((int16ToInt# v2) *# 5#)) +# ((int16ToInt# v3) *# 7#)) +# ((int16ToInt# v4) *# 9#)) +# ((int16ToInt# v5) *# 11#)) +# ((int16ToInt# v6) *# 13#)) +# ((int16ToInt# v7) *# 15#)) +# ((int16ToInt# v8) *# 17#)) +# ((int16ToInt# v9) *# 19#)) +# ((int16ToInt# v10) *# 21#)) +# ((int16ToInt# v11) *# 23#)) +# ((int16ToInt# v12) *# 25#)) +# ((int16ToInt# v13) *# 27#)) +# ((int16ToInt# v14) *# 29#)) +# ((int16ToInt# v15) *# 31#)) } } })

{-# NOINLINE int16X16IndexScalar #-}
int16X16IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int16X16IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt16ArrayAsInt16X16# frozen offset of { vector -> case unpackInt16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int16ToInt# v0) *# 1#) +# ((int16ToInt# v1) *# 3#)) +# ((int16ToInt# v2) *# 5#)) +# ((int16ToInt# v3) *# 7#)) +# ((int16ToInt# v4) *# 9#)) +# ((int16ToInt# v5) *# 11#)) +# ((int16ToInt# v6) *# 13#)) +# ((int16ToInt# v7) *# 15#)) +# ((int16ToInt# v8) *# 17#)) +# ((int16ToInt# v9) *# 19#)) +# ((int16ToInt# v10) *# 21#)) +# ((int16ToInt# v11) *# 23#)) +# ((int16ToInt# v12) *# 25#)) +# ((int16ToInt# v13) *# 27#)) +# ((int16ToInt# v14) *# 29#)) +# ((int16ToInt# v15) *# 31#)) } } })

{-# NOINLINE int16X16ReadPacked #-}
int16X16ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int16X16ReadPacked bytes offset = runRW# (\s -> case readInt16X16Array# bytes offset s of { (# _, vector #) -> case unpackInt16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int16ToInt# v0) *# 1#) +# ((int16ToInt# v1) *# 3#)) +# ((int16ToInt# v2) *# 5#)) +# ((int16ToInt# v3) *# 7#)) +# ((int16ToInt# v4) *# 9#)) +# ((int16ToInt# v5) *# 11#)) +# ((int16ToInt# v6) *# 13#)) +# ((int16ToInt# v7) *# 15#)) +# ((int16ToInt# v8) *# 17#)) +# ((int16ToInt# v9) *# 19#)) +# ((int16ToInt# v10) *# 21#)) +# ((int16ToInt# v11) *# 23#)) +# ((int16ToInt# v12) *# 25#)) +# ((int16ToInt# v13) *# 27#)) +# ((int16ToInt# v14) *# 29#)) +# ((int16ToInt# v15) *# 31#)) } })

{-# NOINLINE int16X16ReadScalar #-}
int16X16ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int16X16ReadScalar bytes offset = runRW# (\s -> case readInt16ArrayAsInt16X16# bytes offset s of { (# _, vector #) -> case unpackInt16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int16ToInt# v0) *# 1#) +# ((int16ToInt# v1) *# 3#)) +# ((int16ToInt# v2) *# 5#)) +# ((int16ToInt# v3) *# 7#)) +# ((int16ToInt# v4) *# 9#)) +# ((int16ToInt# v5) *# 11#)) +# ((int16ToInt# v6) *# 13#)) +# ((int16ToInt# v7) *# 15#)) +# ((int16ToInt# v8) *# 17#)) +# ((int16ToInt# v9) *# 19#)) +# ((int16ToInt# v10) *# 21#)) +# ((int16ToInt# v11) *# 23#)) +# ((int16ToInt# v12) *# 25#)) +# ((int16ToInt# v13) *# 27#)) +# ((int16ToInt# v14) *# 29#)) +# ((int16ToInt# v15) *# 31#)) } })

{-# NOINLINE int16X16WritePacked #-}
int16X16WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int16X16WritePacked bytes offset seed = case packInt16X16# (# intToInt16# (seed *# 23# +# 0#), intToInt16# (seed *# 23# +# 97#), intToInt16# (seed *# 23# +# 194#), intToInt16# (seed *# 23# +# 291#), intToInt16# (seed *# 23# +# 388#), intToInt16# (seed *# 23# +# 485#), intToInt16# (seed *# 23# +# 582#), intToInt16# (seed *# 23# +# 679#), intToInt16# (seed *# 23# +# 776#), intToInt16# (seed *# 23# +# 873#), intToInt16# (seed *# 23# +# 970#), intToInt16# (seed *# 23# +# 1067#), intToInt16# (seed *# 23# +# 1164#), intToInt16# (seed *# 23# +# 1261#), intToInt16# (seed *# 23# +# 1358#), intToInt16# (seed *# 23# +# 1455#) #) of { vector ->
  runRW# (\s -> case writeInt16X16Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int16X16WriteScalar #-}
int16X16WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int16X16WriteScalar bytes offset seed = case packInt16X16# (# intToInt16# (seed *# 23# +# 0#), intToInt16# (seed *# 23# +# 97#), intToInt16# (seed *# 23# +# 194#), intToInt16# (seed *# 23# +# 291#), intToInt16# (seed *# 23# +# 388#), intToInt16# (seed *# 23# +# 485#), intToInt16# (seed *# 23# +# 582#), intToInt16# (seed *# 23# +# 679#), intToInt16# (seed *# 23# +# 776#), intToInt16# (seed *# 23# +# 873#), intToInt16# (seed *# 23# +# 970#), intToInt16# (seed *# 23# +# 1067#), intToInt16# (seed *# 23# +# 1164#), intToInt16# (seed *# 23# +# 1261#), intToInt16# (seed *# 23# +# 1358#), intToInt16# (seed *# 23# +# 1455#) #) of { vector ->
  runRW# (\s -> case writeInt16ArrayAsInt16X16# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word16X16IndexPacked #-}
word16X16IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word16X16IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord16X16Array# frozen offset of { vector -> case unpackWord16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word16ToWord# v0)) *# 1#) +# ((word2Int# (word16ToWord# v1)) *# 3#)) +# ((word2Int# (word16ToWord# v2)) *# 5#)) +# ((word2Int# (word16ToWord# v3)) *# 7#)) +# ((word2Int# (word16ToWord# v4)) *# 9#)) +# ((word2Int# (word16ToWord# v5)) *# 11#)) +# ((word2Int# (word16ToWord# v6)) *# 13#)) +# ((word2Int# (word16ToWord# v7)) *# 15#)) +# ((word2Int# (word16ToWord# v8)) *# 17#)) +# ((word2Int# (word16ToWord# v9)) *# 19#)) +# ((word2Int# (word16ToWord# v10)) *# 21#)) +# ((word2Int# (word16ToWord# v11)) *# 23#)) +# ((word2Int# (word16ToWord# v12)) *# 25#)) +# ((word2Int# (word16ToWord# v13)) *# 27#)) +# ((word2Int# (word16ToWord# v14)) *# 29#)) +# ((word2Int# (word16ToWord# v15)) *# 31#)) } } })

{-# NOINLINE word16X16IndexScalar #-}
word16X16IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word16X16IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord16ArrayAsWord16X16# frozen offset of { vector -> case unpackWord16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word16ToWord# v0)) *# 1#) +# ((word2Int# (word16ToWord# v1)) *# 3#)) +# ((word2Int# (word16ToWord# v2)) *# 5#)) +# ((word2Int# (word16ToWord# v3)) *# 7#)) +# ((word2Int# (word16ToWord# v4)) *# 9#)) +# ((word2Int# (word16ToWord# v5)) *# 11#)) +# ((word2Int# (word16ToWord# v6)) *# 13#)) +# ((word2Int# (word16ToWord# v7)) *# 15#)) +# ((word2Int# (word16ToWord# v8)) *# 17#)) +# ((word2Int# (word16ToWord# v9)) *# 19#)) +# ((word2Int# (word16ToWord# v10)) *# 21#)) +# ((word2Int# (word16ToWord# v11)) *# 23#)) +# ((word2Int# (word16ToWord# v12)) *# 25#)) +# ((word2Int# (word16ToWord# v13)) *# 27#)) +# ((word2Int# (word16ToWord# v14)) *# 29#)) +# ((word2Int# (word16ToWord# v15)) *# 31#)) } } })

{-# NOINLINE word16X16ReadPacked #-}
word16X16ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word16X16ReadPacked bytes offset = runRW# (\s -> case readWord16X16Array# bytes offset s of { (# _, vector #) -> case unpackWord16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word16ToWord# v0)) *# 1#) +# ((word2Int# (word16ToWord# v1)) *# 3#)) +# ((word2Int# (word16ToWord# v2)) *# 5#)) +# ((word2Int# (word16ToWord# v3)) *# 7#)) +# ((word2Int# (word16ToWord# v4)) *# 9#)) +# ((word2Int# (word16ToWord# v5)) *# 11#)) +# ((word2Int# (word16ToWord# v6)) *# 13#)) +# ((word2Int# (word16ToWord# v7)) *# 15#)) +# ((word2Int# (word16ToWord# v8)) *# 17#)) +# ((word2Int# (word16ToWord# v9)) *# 19#)) +# ((word2Int# (word16ToWord# v10)) *# 21#)) +# ((word2Int# (word16ToWord# v11)) *# 23#)) +# ((word2Int# (word16ToWord# v12)) *# 25#)) +# ((word2Int# (word16ToWord# v13)) *# 27#)) +# ((word2Int# (word16ToWord# v14)) *# 29#)) +# ((word2Int# (word16ToWord# v15)) *# 31#)) } })

{-# NOINLINE word16X16ReadScalar #-}
word16X16ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word16X16ReadScalar bytes offset = runRW# (\s -> case readWord16ArrayAsWord16X16# bytes offset s of { (# _, vector #) -> case unpackWord16X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word16ToWord# v0)) *# 1#) +# ((word2Int# (word16ToWord# v1)) *# 3#)) +# ((word2Int# (word16ToWord# v2)) *# 5#)) +# ((word2Int# (word16ToWord# v3)) *# 7#)) +# ((word2Int# (word16ToWord# v4)) *# 9#)) +# ((word2Int# (word16ToWord# v5)) *# 11#)) +# ((word2Int# (word16ToWord# v6)) *# 13#)) +# ((word2Int# (word16ToWord# v7)) *# 15#)) +# ((word2Int# (word16ToWord# v8)) *# 17#)) +# ((word2Int# (word16ToWord# v9)) *# 19#)) +# ((word2Int# (word16ToWord# v10)) *# 21#)) +# ((word2Int# (word16ToWord# v11)) *# 23#)) +# ((word2Int# (word16ToWord# v12)) *# 25#)) +# ((word2Int# (word16ToWord# v13)) *# 27#)) +# ((word2Int# (word16ToWord# v14)) *# 29#)) +# ((word2Int# (word16ToWord# v15)) *# 31#)) } })

{-# NOINLINE word16X16WritePacked #-}
word16X16WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word16X16WritePacked bytes offset seed = case packWord16X16# (# wordToWord16# (int2Word# (seed *# 23# +# 0#)), wordToWord16# (int2Word# (seed *# 23# +# 97#)), wordToWord16# (int2Word# (seed *# 23# +# 194#)), wordToWord16# (int2Word# (seed *# 23# +# 291#)), wordToWord16# (int2Word# (seed *# 23# +# 388#)), wordToWord16# (int2Word# (seed *# 23# +# 485#)), wordToWord16# (int2Word# (seed *# 23# +# 582#)), wordToWord16# (int2Word# (seed *# 23# +# 679#)), wordToWord16# (int2Word# (seed *# 23# +# 776#)), wordToWord16# (int2Word# (seed *# 23# +# 873#)), wordToWord16# (int2Word# (seed *# 23# +# 970#)), wordToWord16# (int2Word# (seed *# 23# +# 1067#)), wordToWord16# (int2Word# (seed *# 23# +# 1164#)), wordToWord16# (int2Word# (seed *# 23# +# 1261#)), wordToWord16# (int2Word# (seed *# 23# +# 1358#)), wordToWord16# (int2Word# (seed *# 23# +# 1455#)) #) of { vector ->
  runRW# (\s -> case writeWord16X16Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word16X16WriteScalar #-}
word16X16WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word16X16WriteScalar bytes offset seed = case packWord16X16# (# wordToWord16# (int2Word# (seed *# 23# +# 0#)), wordToWord16# (int2Word# (seed *# 23# +# 97#)), wordToWord16# (int2Word# (seed *# 23# +# 194#)), wordToWord16# (int2Word# (seed *# 23# +# 291#)), wordToWord16# (int2Word# (seed *# 23# +# 388#)), wordToWord16# (int2Word# (seed *# 23# +# 485#)), wordToWord16# (int2Word# (seed *# 23# +# 582#)), wordToWord16# (int2Word# (seed *# 23# +# 679#)), wordToWord16# (int2Word# (seed *# 23# +# 776#)), wordToWord16# (int2Word# (seed *# 23# +# 873#)), wordToWord16# (int2Word# (seed *# 23# +# 970#)), wordToWord16# (int2Word# (seed *# 23# +# 1067#)), wordToWord16# (int2Word# (seed *# 23# +# 1164#)), wordToWord16# (int2Word# (seed *# 23# +# 1261#)), wordToWord16# (int2Word# (seed *# 23# +# 1358#)), wordToWord16# (int2Word# (seed *# 23# +# 1455#)) #) of { vector ->
  runRW# (\s -> case writeWord16ArrayAsWord16X16# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int32X8IndexPacked #-}
int32X8IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int32X8IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt32X8Array# frozen offset of { vector -> case unpackInt32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) } } })

{-# NOINLINE int32X8IndexScalar #-}
int32X8IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int32X8IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt32ArrayAsInt32X8# frozen offset of { vector -> case unpackInt32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) } } })

{-# NOINLINE int32X8ReadPacked #-}
int32X8ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int32X8ReadPacked bytes offset = runRW# (\s -> case readInt32X8Array# bytes offset s of { (# _, vector #) -> case unpackInt32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) } })

{-# NOINLINE int32X8ReadScalar #-}
int32X8ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int32X8ReadScalar bytes offset = runRW# (\s -> case readInt32ArrayAsInt32X8# bytes offset s of { (# _, vector #) -> case unpackInt32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) } })

{-# NOINLINE int32X8WritePacked #-}
int32X8WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int32X8WritePacked bytes offset seed = case packInt32X8# (# intToInt32# (seed *# 23# +# 0#), intToInt32# (seed *# 23# +# 97#), intToInt32# (seed *# 23# +# 194#), intToInt32# (seed *# 23# +# 291#), intToInt32# (seed *# 23# +# 388#), intToInt32# (seed *# 23# +# 485#), intToInt32# (seed *# 23# +# 582#), intToInt32# (seed *# 23# +# 679#) #) of { vector ->
  runRW# (\s -> case writeInt32X8Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int32X8WriteScalar #-}
int32X8WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int32X8WriteScalar bytes offset seed = case packInt32X8# (# intToInt32# (seed *# 23# +# 0#), intToInt32# (seed *# 23# +# 97#), intToInt32# (seed *# 23# +# 194#), intToInt32# (seed *# 23# +# 291#), intToInt32# (seed *# 23# +# 388#), intToInt32# (seed *# 23# +# 485#), intToInt32# (seed *# 23# +# 582#), intToInt32# (seed *# 23# +# 679#) #) of { vector ->
  runRW# (\s -> case writeInt32ArrayAsInt32X8# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word32X8IndexPacked #-}
word32X8IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word32X8IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord32X8Array# frozen offset of { vector -> case unpackWord32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) } } })

{-# NOINLINE word32X8IndexScalar #-}
word32X8IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word32X8IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord32ArrayAsWord32X8# frozen offset of { vector -> case unpackWord32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) } } })

{-# NOINLINE word32X8ReadPacked #-}
word32X8ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word32X8ReadPacked bytes offset = runRW# (\s -> case readWord32X8Array# bytes offset s of { (# _, vector #) -> case unpackWord32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) } })

{-# NOINLINE word32X8ReadScalar #-}
word32X8ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word32X8ReadScalar bytes offset = runRW# (\s -> case readWord32ArrayAsWord32X8# bytes offset s of { (# _, vector #) -> case unpackWord32X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) } })

{-# NOINLINE word32X8WritePacked #-}
word32X8WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word32X8WritePacked bytes offset seed = case packWord32X8# (# wordToWord32# (int2Word# (seed *# 23# +# 0#)), wordToWord32# (int2Word# (seed *# 23# +# 97#)), wordToWord32# (int2Word# (seed *# 23# +# 194#)), wordToWord32# (int2Word# (seed *# 23# +# 291#)), wordToWord32# (int2Word# (seed *# 23# +# 388#)), wordToWord32# (int2Word# (seed *# 23# +# 485#)), wordToWord32# (int2Word# (seed *# 23# +# 582#)), wordToWord32# (int2Word# (seed *# 23# +# 679#)) #) of { vector ->
  runRW# (\s -> case writeWord32X8Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word32X8WriteScalar #-}
word32X8WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word32X8WriteScalar bytes offset seed = case packWord32X8# (# wordToWord32# (int2Word# (seed *# 23# +# 0#)), wordToWord32# (int2Word# (seed *# 23# +# 97#)), wordToWord32# (int2Word# (seed *# 23# +# 194#)), wordToWord32# (int2Word# (seed *# 23# +# 291#)), wordToWord32# (int2Word# (seed *# 23# +# 388#)), wordToWord32# (int2Word# (seed *# 23# +# 485#)), wordToWord32# (int2Word# (seed *# 23# +# 582#)), wordToWord32# (int2Word# (seed *# 23# +# 679#)) #) of { vector ->
  runRW# (\s -> case writeWord32ArrayAsWord32X8# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int32X16IndexPacked #-}
int32X16IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int32X16IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt32X16Array# frozen offset of { vector -> case unpackInt32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) +# ((int32ToInt# v8) *# 17#)) +# ((int32ToInt# v9) *# 19#)) +# ((int32ToInt# v10) *# 21#)) +# ((int32ToInt# v11) *# 23#)) +# ((int32ToInt# v12) *# 25#)) +# ((int32ToInt# v13) *# 27#)) +# ((int32ToInt# v14) *# 29#)) +# ((int32ToInt# v15) *# 31#)) } } })

{-# NOINLINE int32X16IndexScalar #-}
int32X16IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int32X16IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt32ArrayAsInt32X16# frozen offset of { vector -> case unpackInt32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) +# ((int32ToInt# v8) *# 17#)) +# ((int32ToInt# v9) *# 19#)) +# ((int32ToInt# v10) *# 21#)) +# ((int32ToInt# v11) *# 23#)) +# ((int32ToInt# v12) *# 25#)) +# ((int32ToInt# v13) *# 27#)) +# ((int32ToInt# v14) *# 29#)) +# ((int32ToInt# v15) *# 31#)) } } })

{-# NOINLINE int32X16ReadPacked #-}
int32X16ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int32X16ReadPacked bytes offset = runRW# (\s -> case readInt32X16Array# bytes offset s of { (# _, vector #) -> case unpackInt32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) +# ((int32ToInt# v8) *# 17#)) +# ((int32ToInt# v9) *# 19#)) +# ((int32ToInt# v10) *# 21#)) +# ((int32ToInt# v11) *# 23#)) +# ((int32ToInt# v12) *# 25#)) +# ((int32ToInt# v13) *# 27#)) +# ((int32ToInt# v14) *# 29#)) +# ((int32ToInt# v15) *# 31#)) } })

{-# NOINLINE int32X16ReadScalar #-}
int32X16ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int32X16ReadScalar bytes offset = runRW# (\s -> case readInt32ArrayAsInt32X16# bytes offset s of { (# _, vector #) -> case unpackInt32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((int32ToInt# v0) *# 1#) +# ((int32ToInt# v1) *# 3#)) +# ((int32ToInt# v2) *# 5#)) +# ((int32ToInt# v3) *# 7#)) +# ((int32ToInt# v4) *# 9#)) +# ((int32ToInt# v5) *# 11#)) +# ((int32ToInt# v6) *# 13#)) +# ((int32ToInt# v7) *# 15#)) +# ((int32ToInt# v8) *# 17#)) +# ((int32ToInt# v9) *# 19#)) +# ((int32ToInt# v10) *# 21#)) +# ((int32ToInt# v11) *# 23#)) +# ((int32ToInt# v12) *# 25#)) +# ((int32ToInt# v13) *# 27#)) +# ((int32ToInt# v14) *# 29#)) +# ((int32ToInt# v15) *# 31#)) } })

{-# NOINLINE int32X16WritePacked #-}
int32X16WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int32X16WritePacked bytes offset seed = case packInt32X16# (# intToInt32# (seed *# 23# +# 0#), intToInt32# (seed *# 23# +# 97#), intToInt32# (seed *# 23# +# 194#), intToInt32# (seed *# 23# +# 291#), intToInt32# (seed *# 23# +# 388#), intToInt32# (seed *# 23# +# 485#), intToInt32# (seed *# 23# +# 582#), intToInt32# (seed *# 23# +# 679#), intToInt32# (seed *# 23# +# 776#), intToInt32# (seed *# 23# +# 873#), intToInt32# (seed *# 23# +# 970#), intToInt32# (seed *# 23# +# 1067#), intToInt32# (seed *# 23# +# 1164#), intToInt32# (seed *# 23# +# 1261#), intToInt32# (seed *# 23# +# 1358#), intToInt32# (seed *# 23# +# 1455#) #) of { vector ->
  runRW# (\s -> case writeInt32X16Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int32X16WriteScalar #-}
int32X16WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int32X16WriteScalar bytes offset seed = case packInt32X16# (# intToInt32# (seed *# 23# +# 0#), intToInt32# (seed *# 23# +# 97#), intToInt32# (seed *# 23# +# 194#), intToInt32# (seed *# 23# +# 291#), intToInt32# (seed *# 23# +# 388#), intToInt32# (seed *# 23# +# 485#), intToInt32# (seed *# 23# +# 582#), intToInt32# (seed *# 23# +# 679#), intToInt32# (seed *# 23# +# 776#), intToInt32# (seed *# 23# +# 873#), intToInt32# (seed *# 23# +# 970#), intToInt32# (seed *# 23# +# 1067#), intToInt32# (seed *# 23# +# 1164#), intToInt32# (seed *# 23# +# 1261#), intToInt32# (seed *# 23# +# 1358#), intToInt32# (seed *# 23# +# 1455#) #) of { vector ->
  runRW# (\s -> case writeInt32ArrayAsInt32X16# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word32X16IndexPacked #-}
word32X16IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word32X16IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord32X16Array# frozen offset of { vector -> case unpackWord32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) +# ((word2Int# (word32ToWord# v8)) *# 17#)) +# ((word2Int# (word32ToWord# v9)) *# 19#)) +# ((word2Int# (word32ToWord# v10)) *# 21#)) +# ((word2Int# (word32ToWord# v11)) *# 23#)) +# ((word2Int# (word32ToWord# v12)) *# 25#)) +# ((word2Int# (word32ToWord# v13)) *# 27#)) +# ((word2Int# (word32ToWord# v14)) *# 29#)) +# ((word2Int# (word32ToWord# v15)) *# 31#)) } } })

{-# NOINLINE word32X16IndexScalar #-}
word32X16IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word32X16IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord32ArrayAsWord32X16# frozen offset of { vector -> case unpackWord32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) +# ((word2Int# (word32ToWord# v8)) *# 17#)) +# ((word2Int# (word32ToWord# v9)) *# 19#)) +# ((word2Int# (word32ToWord# v10)) *# 21#)) +# ((word2Int# (word32ToWord# v11)) *# 23#)) +# ((word2Int# (word32ToWord# v12)) *# 25#)) +# ((word2Int# (word32ToWord# v13)) *# 27#)) +# ((word2Int# (word32ToWord# v14)) *# 29#)) +# ((word2Int# (word32ToWord# v15)) *# 31#)) } } })

{-# NOINLINE word32X16ReadPacked #-}
word32X16ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word32X16ReadPacked bytes offset = runRW# (\s -> case readWord32X16Array# bytes offset s of { (# _, vector #) -> case unpackWord32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) +# ((word2Int# (word32ToWord# v8)) *# 17#)) +# ((word2Int# (word32ToWord# v9)) *# 19#)) +# ((word2Int# (word32ToWord# v10)) *# 21#)) +# ((word2Int# (word32ToWord# v11)) *# 23#)) +# ((word2Int# (word32ToWord# v12)) *# 25#)) +# ((word2Int# (word32ToWord# v13)) *# 27#)) +# ((word2Int# (word32ToWord# v14)) *# 29#)) +# ((word2Int# (word32ToWord# v15)) *# 31#)) } })

{-# NOINLINE word32X16ReadScalar #-}
word32X16ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word32X16ReadScalar bytes offset = runRW# (\s -> case readWord32ArrayAsWord32X16# bytes offset s of { (# _, vector #) -> case unpackWord32X16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# v0)) *# 1#) +# ((word2Int# (word32ToWord# v1)) *# 3#)) +# ((word2Int# (word32ToWord# v2)) *# 5#)) +# ((word2Int# (word32ToWord# v3)) *# 7#)) +# ((word2Int# (word32ToWord# v4)) *# 9#)) +# ((word2Int# (word32ToWord# v5)) *# 11#)) +# ((word2Int# (word32ToWord# v6)) *# 13#)) +# ((word2Int# (word32ToWord# v7)) *# 15#)) +# ((word2Int# (word32ToWord# v8)) *# 17#)) +# ((word2Int# (word32ToWord# v9)) *# 19#)) +# ((word2Int# (word32ToWord# v10)) *# 21#)) +# ((word2Int# (word32ToWord# v11)) *# 23#)) +# ((word2Int# (word32ToWord# v12)) *# 25#)) +# ((word2Int# (word32ToWord# v13)) *# 27#)) +# ((word2Int# (word32ToWord# v14)) *# 29#)) +# ((word2Int# (word32ToWord# v15)) *# 31#)) } })

{-# NOINLINE word32X16WritePacked #-}
word32X16WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word32X16WritePacked bytes offset seed = case packWord32X16# (# wordToWord32# (int2Word# (seed *# 23# +# 0#)), wordToWord32# (int2Word# (seed *# 23# +# 97#)), wordToWord32# (int2Word# (seed *# 23# +# 194#)), wordToWord32# (int2Word# (seed *# 23# +# 291#)), wordToWord32# (int2Word# (seed *# 23# +# 388#)), wordToWord32# (int2Word# (seed *# 23# +# 485#)), wordToWord32# (int2Word# (seed *# 23# +# 582#)), wordToWord32# (int2Word# (seed *# 23# +# 679#)), wordToWord32# (int2Word# (seed *# 23# +# 776#)), wordToWord32# (int2Word# (seed *# 23# +# 873#)), wordToWord32# (int2Word# (seed *# 23# +# 970#)), wordToWord32# (int2Word# (seed *# 23# +# 1067#)), wordToWord32# (int2Word# (seed *# 23# +# 1164#)), wordToWord32# (int2Word# (seed *# 23# +# 1261#)), wordToWord32# (int2Word# (seed *# 23# +# 1358#)), wordToWord32# (int2Word# (seed *# 23# +# 1455#)) #) of { vector ->
  runRW# (\s -> case writeWord32X16Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word32X16WriteScalar #-}
word32X16WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word32X16WriteScalar bytes offset seed = case packWord32X16# (# wordToWord32# (int2Word# (seed *# 23# +# 0#)), wordToWord32# (int2Word# (seed *# 23# +# 97#)), wordToWord32# (int2Word# (seed *# 23# +# 194#)), wordToWord32# (int2Word# (seed *# 23# +# 291#)), wordToWord32# (int2Word# (seed *# 23# +# 388#)), wordToWord32# (int2Word# (seed *# 23# +# 485#)), wordToWord32# (int2Word# (seed *# 23# +# 582#)), wordToWord32# (int2Word# (seed *# 23# +# 679#)), wordToWord32# (int2Word# (seed *# 23# +# 776#)), wordToWord32# (int2Word# (seed *# 23# +# 873#)), wordToWord32# (int2Word# (seed *# 23# +# 970#)), wordToWord32# (int2Word# (seed *# 23# +# 1067#)), wordToWord32# (int2Word# (seed *# 23# +# 1164#)), wordToWord32# (int2Word# (seed *# 23# +# 1261#)), wordToWord32# (int2Word# (seed *# 23# +# 1358#)), wordToWord32# (int2Word# (seed *# 23# +# 1455#)) #) of { vector ->
  runRW# (\s -> case writeWord32ArrayAsWord32X16# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int64X4IndexPacked #-}
int64X4IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int64X4IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt64X4Array# frozen offset of { vector -> case unpackInt64X4# vector of { (# v0, v1, v2, v3 #) -> (((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) } } })

{-# NOINLINE int64X4IndexScalar #-}
int64X4IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int64X4IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt64ArrayAsInt64X4# frozen offset of { vector -> case unpackInt64X4# vector of { (# v0, v1, v2, v3 #) -> (((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) } } })

{-# NOINLINE int64X4ReadPacked #-}
int64X4ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int64X4ReadPacked bytes offset = runRW# (\s -> case readInt64X4Array# bytes offset s of { (# _, vector #) -> case unpackInt64X4# vector of { (# v0, v1, v2, v3 #) -> (((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) } })

{-# NOINLINE int64X4ReadScalar #-}
int64X4ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int64X4ReadScalar bytes offset = runRW# (\s -> case readInt64ArrayAsInt64X4# bytes offset s of { (# _, vector #) -> case unpackInt64X4# vector of { (# v0, v1, v2, v3 #) -> (((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) } })

{-# NOINLINE int64X4WritePacked #-}
int64X4WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int64X4WritePacked bytes offset seed = case packInt64X4# (# intToInt64# (seed *# 23# +# 0#), intToInt64# (seed *# 23# +# 97#), intToInt64# (seed *# 23# +# 194#), intToInt64# (seed *# 23# +# 291#) #) of { vector ->
  runRW# (\s -> case writeInt64X4Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int64X4WriteScalar #-}
int64X4WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int64X4WriteScalar bytes offset seed = case packInt64X4# (# intToInt64# (seed *# 23# +# 0#), intToInt64# (seed *# 23# +# 97#), intToInt64# (seed *# 23# +# 194#), intToInt64# (seed *# 23# +# 291#) #) of { vector ->
  runRW# (\s -> case writeInt64ArrayAsInt64X4# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word64X4IndexPacked #-}
word64X4IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word64X4IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord64X4Array# frozen offset of { vector -> case unpackWord64X4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) } } })

{-# NOINLINE word64X4IndexScalar #-}
word64X4IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word64X4IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord64ArrayAsWord64X4# frozen offset of { vector -> case unpackWord64X4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) } } })

{-# NOINLINE word64X4ReadPacked #-}
word64X4ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word64X4ReadPacked bytes offset = runRW# (\s -> case readWord64X4Array# bytes offset s of { (# _, vector #) -> case unpackWord64X4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) } })

{-# NOINLINE word64X4ReadScalar #-}
word64X4ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word64X4ReadScalar bytes offset = runRW# (\s -> case readWord64ArrayAsWord64X4# bytes offset s of { (# _, vector #) -> case unpackWord64X4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) } })

{-# NOINLINE word64X4WritePacked #-}
word64X4WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word64X4WritePacked bytes offset seed = case packWord64X4# (# wordToWord64# (int2Word# (seed *# 23# +# 0#)), wordToWord64# (int2Word# (seed *# 23# +# 97#)), wordToWord64# (int2Word# (seed *# 23# +# 194#)), wordToWord64# (int2Word# (seed *# 23# +# 291#)) #) of { vector ->
  runRW# (\s -> case writeWord64X4Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word64X4WriteScalar #-}
word64X4WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word64X4WriteScalar bytes offset seed = case packWord64X4# (# wordToWord64# (int2Word# (seed *# 23# +# 0#)), wordToWord64# (int2Word# (seed *# 23# +# 97#)), wordToWord64# (int2Word# (seed *# 23# +# 194#)), wordToWord64# (int2Word# (seed *# 23# +# 291#)) #) of { vector ->
  runRW# (\s -> case writeWord64ArrayAsWord64X4# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int64X8IndexPacked #-}
int64X8IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int64X8IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt64X8Array# frozen offset of { vector -> case unpackInt64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) +# ((int64ToInt# v4) *# 9#)) +# ((int64ToInt# v5) *# 11#)) +# ((int64ToInt# v6) *# 13#)) +# ((int64ToInt# v7) *# 15#)) } } })

{-# NOINLINE int64X8IndexScalar #-}
int64X8IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int64X8IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexInt64ArrayAsInt64X8# frozen offset of { vector -> case unpackInt64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) +# ((int64ToInt# v4) *# 9#)) +# ((int64ToInt# v5) *# 11#)) +# ((int64ToInt# v6) *# 13#)) +# ((int64ToInt# v7) *# 15#)) } } })

{-# NOINLINE int64X8ReadPacked #-}
int64X8ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
int64X8ReadPacked bytes offset = runRW# (\s -> case readInt64X8Array# bytes offset s of { (# _, vector #) -> case unpackInt64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) +# ((int64ToInt# v4) *# 9#)) +# ((int64ToInt# v5) *# 11#)) +# ((int64ToInt# v6) *# 13#)) +# ((int64ToInt# v7) *# 15#)) } })

{-# NOINLINE int64X8ReadScalar #-}
int64X8ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
int64X8ReadScalar bytes offset = runRW# (\s -> case readInt64ArrayAsInt64X8# bytes offset s of { (# _, vector #) -> case unpackInt64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((int64ToInt# v0) *# 1#) +# ((int64ToInt# v1) *# 3#)) +# ((int64ToInt# v2) *# 5#)) +# ((int64ToInt# v3) *# 7#)) +# ((int64ToInt# v4) *# 9#)) +# ((int64ToInt# v5) *# 11#)) +# ((int64ToInt# v6) *# 13#)) +# ((int64ToInt# v7) *# 15#)) } })

{-# NOINLINE int64X8WritePacked #-}
int64X8WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int64X8WritePacked bytes offset seed = case packInt64X8# (# intToInt64# (seed *# 23# +# 0#), intToInt64# (seed *# 23# +# 97#), intToInt64# (seed *# 23# +# 194#), intToInt64# (seed *# 23# +# 291#), intToInt64# (seed *# 23# +# 388#), intToInt64# (seed *# 23# +# 485#), intToInt64# (seed *# 23# +# 582#), intToInt64# (seed *# 23# +# 679#) #) of { vector ->
  runRW# (\s -> case writeInt64X8Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE int64X8WriteScalar #-}
int64X8WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
int64X8WriteScalar bytes offset seed = case packInt64X8# (# intToInt64# (seed *# 23# +# 0#), intToInt64# (seed *# 23# +# 97#), intToInt64# (seed *# 23# +# 194#), intToInt64# (seed *# 23# +# 291#), intToInt64# (seed *# 23# +# 388#), intToInt64# (seed *# 23# +# 485#), intToInt64# (seed *# 23# +# 582#), intToInt64# (seed *# 23# +# 679#) #) of { vector ->
  runRW# (\s -> case writeInt64ArrayAsInt64X8# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word64X8IndexPacked #-}
word64X8IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word64X8IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord64X8Array# frozen offset of { vector -> case unpackWord64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) +# ((word2Int# (word64ToWord# v4)) *# 9#)) +# ((word2Int# (word64ToWord# v5)) *# 11#)) +# ((word2Int# (word64ToWord# v6)) *# 13#)) +# ((word2Int# (word64ToWord# v7)) *# 15#)) } } })

{-# NOINLINE word64X8IndexScalar #-}
word64X8IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word64X8IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexWord64ArrayAsWord64X8# frozen offset of { vector -> case unpackWord64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) +# ((word2Int# (word64ToWord# v4)) *# 9#)) +# ((word2Int# (word64ToWord# v5)) *# 11#)) +# ((word2Int# (word64ToWord# v6)) *# 13#)) +# ((word2Int# (word64ToWord# v7)) *# 15#)) } } })

{-# NOINLINE word64X8ReadPacked #-}
word64X8ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
word64X8ReadPacked bytes offset = runRW# (\s -> case readWord64X8Array# bytes offset s of { (# _, vector #) -> case unpackWord64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) +# ((word2Int# (word64ToWord# v4)) *# 9#)) +# ((word2Int# (word64ToWord# v5)) *# 11#)) +# ((word2Int# (word64ToWord# v6)) *# 13#)) +# ((word2Int# (word64ToWord# v7)) *# 15#)) } })

{-# NOINLINE word64X8ReadScalar #-}
word64X8ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
word64X8ReadScalar bytes offset = runRW# (\s -> case readWord64ArrayAsWord64X8# bytes offset s of { (# _, vector #) -> case unpackWord64X8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# v0)) *# 1#) +# ((word2Int# (word64ToWord# v1)) *# 3#)) +# ((word2Int# (word64ToWord# v2)) *# 5#)) +# ((word2Int# (word64ToWord# v3)) *# 7#)) +# ((word2Int# (word64ToWord# v4)) *# 9#)) +# ((word2Int# (word64ToWord# v5)) *# 11#)) +# ((word2Int# (word64ToWord# v6)) *# 13#)) +# ((word2Int# (word64ToWord# v7)) *# 15#)) } })

{-# NOINLINE word64X8WritePacked #-}
word64X8WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word64X8WritePacked bytes offset seed = case packWord64X8# (# wordToWord64# (int2Word# (seed *# 23# +# 0#)), wordToWord64# (int2Word# (seed *# 23# +# 97#)), wordToWord64# (int2Word# (seed *# 23# +# 194#)), wordToWord64# (int2Word# (seed *# 23# +# 291#)), wordToWord64# (int2Word# (seed *# 23# +# 388#)), wordToWord64# (int2Word# (seed *# 23# +# 485#)), wordToWord64# (int2Word# (seed *# 23# +# 582#)), wordToWord64# (int2Word# (seed *# 23# +# 679#)) #) of { vector ->
  runRW# (\s -> case writeWord64X8Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE word64X8WriteScalar #-}
word64X8WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
word64X8WriteScalar bytes offset seed = case packWord64X8# (# wordToWord64# (int2Word# (seed *# 23# +# 0#)), wordToWord64# (int2Word# (seed *# 23# +# 97#)), wordToWord64# (int2Word# (seed *# 23# +# 194#)), wordToWord64# (int2Word# (seed *# 23# +# 291#)), wordToWord64# (int2Word# (seed *# 23# +# 388#)), wordToWord64# (int2Word# (seed *# 23# +# 485#)), wordToWord64# (int2Word# (seed *# 23# +# 582#)), wordToWord64# (int2Word# (seed *# 23# +# 679#)) #) of { vector ->
  runRW# (\s -> case writeWord64ArrayAsWord64X8# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE floatX8IndexPacked #-}
floatX8IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
floatX8IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexFloatX8Array# frozen offset of { vector -> case unpackFloatX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) } } })

{-# NOINLINE floatX8IndexScalar #-}
floatX8IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
floatX8IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexFloatArrayAsFloatX8# frozen offset of { vector -> case unpackFloatX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) } } })

{-# NOINLINE floatX8ReadPacked #-}
floatX8ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
floatX8ReadPacked bytes offset = runRW# (\s -> case readFloatX8Array# bytes offset s of { (# _, vector #) -> case unpackFloatX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) } })

{-# NOINLINE floatX8ReadScalar #-}
floatX8ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
floatX8ReadScalar bytes offset = runRW# (\s -> case readFloatArrayAsFloatX8# bytes offset s of { (# _, vector #) -> case unpackFloatX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) } })

{-# NOINLINE floatX8WritePacked #-}
floatX8WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
floatX8WritePacked bytes offset seed = case packFloatX8# (# castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 0#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 97#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 194#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 291#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 388#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 485#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 582#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 679#))) #) of { vector ->
  runRW# (\s -> case writeFloatX8Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE floatX8WriteScalar #-}
floatX8WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
floatX8WriteScalar bytes offset seed = case packFloatX8# (# castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 0#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 97#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 194#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 291#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 388#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 485#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 582#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 679#))) #) of { vector ->
  runRW# (\s -> case writeFloatArrayAsFloatX8# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE floatX16IndexPacked #-}
floatX16IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
floatX16IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexFloatX16Array# frozen offset of { vector -> case unpackFloatX16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v8))) *# 17#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v9))) *# 19#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v10))) *# 21#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v11))) *# 23#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v12))) *# 25#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v13))) *# 27#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v14))) *# 29#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v15))) *# 31#)) } } })

{-# NOINLINE floatX16IndexScalar #-}
floatX16IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
floatX16IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexFloatArrayAsFloatX16# frozen offset of { vector -> case unpackFloatX16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v8))) *# 17#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v9))) *# 19#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v10))) *# 21#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v11))) *# 23#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v12))) *# 25#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v13))) *# 27#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v14))) *# 29#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v15))) *# 31#)) } } })

{-# NOINLINE floatX16ReadPacked #-}
floatX16ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
floatX16ReadPacked bytes offset = runRW# (\s -> case readFloatX16Array# bytes offset s of { (# _, vector #) -> case unpackFloatX16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v8))) *# 17#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v9))) *# 19#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v10))) *# 21#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v11))) *# 23#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v12))) *# 25#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v13))) *# 27#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v14))) *# 29#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v15))) *# 31#)) } })

{-# NOINLINE floatX16ReadScalar #-}
floatX16ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
floatX16ReadScalar bytes offset = runRW# (\s -> case readFloatArrayAsFloatX16# bytes offset s of { (# _, vector #) -> case unpackFloatX16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) -> (((((((((((((((((word2Int# (word32ToWord# (castFloatToWord32# v0))) *# 1#) +# ((word2Int# (word32ToWord# (castFloatToWord32# v1))) *# 3#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v2))) *# 5#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v3))) *# 7#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v4))) *# 9#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v5))) *# 11#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v6))) *# 13#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v7))) *# 15#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v8))) *# 17#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v9))) *# 19#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v10))) *# 21#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v11))) *# 23#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v12))) *# 25#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v13))) *# 27#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v14))) *# 29#)) +# ((word2Int# (word32ToWord# (castFloatToWord32# v15))) *# 31#)) } })

{-# NOINLINE floatX16WritePacked #-}
floatX16WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
floatX16WritePacked bytes offset seed = case packFloatX16# (# castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 0#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 97#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 194#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 291#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 388#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 485#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 582#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 679#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 776#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 873#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 970#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1067#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1164#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1261#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1358#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1455#))) #) of { vector ->
  runRW# (\s -> case writeFloatX16Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE floatX16WriteScalar #-}
floatX16WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
floatX16WriteScalar bytes offset seed = case packFloatX16# (# castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 0#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 97#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 194#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 291#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 388#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 485#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 582#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 679#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 776#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 873#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 970#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1067#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1164#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1261#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1358#))), castWord32ToFloat# (wordToWord32# (int2Word# (seed *# 23# +# 1455#))) #) of { vector ->
  runRW# (\s -> case writeFloatArrayAsFloatX16# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE doubleX4IndexPacked #-}
doubleX4IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX4IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexDoubleX4Array# frozen offset of { vector -> case unpackDoubleX4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) } } })

{-# NOINLINE doubleX4IndexScalar #-}
doubleX4IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX4IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexDoubleArrayAsDoubleX4# frozen offset of { vector -> case unpackDoubleX4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) } } })

{-# NOINLINE doubleX4ReadPacked #-}
doubleX4ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX4ReadPacked bytes offset = runRW# (\s -> case readDoubleX4Array# bytes offset s of { (# _, vector #) -> case unpackDoubleX4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) } })

{-# NOINLINE doubleX4ReadScalar #-}
doubleX4ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX4ReadScalar bytes offset = runRW# (\s -> case readDoubleArrayAsDoubleX4# bytes offset s of { (# _, vector #) -> case unpackDoubleX4# vector of { (# v0, v1, v2, v3 #) -> (((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) } })

{-# NOINLINE doubleX4WritePacked #-}
doubleX4WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
doubleX4WritePacked bytes offset seed = case packDoubleX4# (# castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 0#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 97#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 194#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 291#))) #) of { vector ->
  runRW# (\s -> case writeDoubleX4Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE doubleX4WriteScalar #-}
doubleX4WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
doubleX4WriteScalar bytes offset seed = case packDoubleX4# (# castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 0#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 97#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 194#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 291#))) #) of { vector ->
  runRW# (\s -> case writeDoubleArrayAsDoubleX4# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE doubleX8IndexPacked #-}
doubleX8IndexPacked :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX8IndexPacked bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexDoubleX8Array# frozen offset of { vector -> case unpackDoubleX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v4))) *# 9#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v5))) *# 11#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v6))) *# 13#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v7))) *# 15#)) } } })

{-# NOINLINE doubleX8IndexScalar #-}
doubleX8IndexScalar :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX8IndexScalar bytes offset = runRW# (\s -> case unsafeFreezeByteArray# bytes s of { (# _, frozen #) -> case indexDoubleArrayAsDoubleX8# frozen offset of { vector -> case unpackDoubleX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v4))) *# 9#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v5))) *# 11#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v6))) *# 13#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v7))) *# 15#)) } } })

{-# NOINLINE doubleX8ReadPacked #-}
doubleX8ReadPacked :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX8ReadPacked bytes offset = runRW# (\s -> case readDoubleX8Array# bytes offset s of { (# _, vector #) -> case unpackDoubleX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v4))) *# 9#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v5))) *# 11#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v6))) *# 13#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v7))) *# 15#)) } })

{-# NOINLINE doubleX8ReadScalar #-}
doubleX8ReadScalar :: MutableByteArray# RealWorld -> Int# -> Int#
doubleX8ReadScalar bytes offset = runRW# (\s -> case readDoubleArrayAsDoubleX8# bytes offset s of { (# _, vector #) -> case unpackDoubleX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) -> (((((((((word2Int# (word64ToWord# (castDoubleToWord64# v0))) *# 1#) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v1))) *# 3#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v2))) *# 5#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v3))) *# 7#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v4))) *# 9#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v5))) *# 11#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v6))) *# 13#)) +# ((word2Int# (word64ToWord# (castDoubleToWord64# v7))) *# 15#)) } })

{-# NOINLINE doubleX8WritePacked #-}
doubleX8WritePacked :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
doubleX8WritePacked bytes offset seed = case packDoubleX8# (# castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 0#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 97#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 194#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 291#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 388#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 485#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 582#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 679#))) #) of { vector ->
  runRW# (\s -> case writeDoubleX8Array# bytes offset vector s of { _ -> 0# }) }

{-# NOINLINE doubleX8WriteScalar #-}
doubleX8WriteScalar :: MutableByteArray# RealWorld -> Int# -> Int# -> Int#
doubleX8WriteScalar bytes offset seed = case packDoubleX8# (# castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 0#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 97#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 194#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 291#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 388#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 485#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 582#))), castWord64ToDouble# (wordToWord64# (int2Word# (seed *# 23# +# 679#))) #) of { vector ->
  runRW# (\s -> case writeDoubleArrayAsDoubleX8# bytes offset vector s of { _ -> 0# }) }
