-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdWord16X8 where

import GHC.Exts

-- Vectors remain local. Lanes wrap modulo 65536 and zero-extend before the
-- distinct-weight machine-Int checksum, safe on the required 64-bit host.

plusCase :: Int# -> Int# -> Int#
plusCase a b =
  case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
    x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
      y -> case unpackWord16X8# (plusWord16X8# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#)

minusCase :: Int# -> Int# -> Int#
minusCase a b =
  case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
    x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
      y -> case unpackWord16X8# (minusWord16X8# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#)

timesCase :: Int# -> Int# -> Int#
timesCase a b =
  case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
    x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
      y -> case unpackWord16X8# (timesWord16X8# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#)

packCase :: Int# -> Int# -> Int#
packCase a b =
  case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
    x -> case unpackWord16X8# x of
      (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#)

broadcastCase :: Int# -> Int# -> Int#
broadcastCase a b =
  case unpackWord16X8# (broadcastWord16X8# (plusWord16# (wordToWord16# (int2Word# (a -# b))) (wordToWord16# 29##))) of
    (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#)

-- Every branch finishes its unsigned vector work and returns only Int#.
-- Operations 0..4 are plus, minus, times, pack, broadcast. There is no negate.
laneCase :: Int# -> Int# -> Int# -> Int# -> Int#
laneCase operation lane a b = case operation of
  0# ->
    case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
      x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
        y -> case unpackWord16X8# (plusWord16X8# x y) of
          (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> case lane of { 0# -> word2Int# (word16ToWord# p0); 1# -> word2Int# (word16ToWord# p1); 2# -> word2Int# (word16ToWord# p2); 3# -> word2Int# (word16ToWord# p3); 4# -> word2Int# (word16ToWord# p4); 5# -> word2Int# (word16ToWord# p5); 6# -> word2Int# (word16ToWord# p6); _ -> word2Int# (word16ToWord# p7) }
  1# ->
    case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
      x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
        y -> case unpackWord16X8# (minusWord16X8# x y) of
          (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> case lane of { 0# -> word2Int# (word16ToWord# p0); 1# -> word2Int# (word16ToWord# p1); 2# -> word2Int# (word16ToWord# p2); 3# -> word2Int# (word16ToWord# p3); 4# -> word2Int# (word16ToWord# p4); 5# -> word2Int# (word16ToWord# p5); 6# -> word2Int# (word16ToWord# p6); _ -> word2Int# (word16ToWord# p7) }
  2# ->
    case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
      x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
        y -> case unpackWord16X8# (timesWord16X8# x y) of
          (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> case lane of { 0# -> word2Int# (word16ToWord# p0); 1# -> word2Int# (word16ToWord# p1); 2# -> word2Int# (word16ToWord# p2); 3# -> word2Int# (word16ToWord# p3); 4# -> word2Int# (word16ToWord# p4); 5# -> word2Int# (word16ToWord# p5); 6# -> word2Int# (word16ToWord# p6); _ -> word2Int# (word16ToWord# p7) }
  3# ->
    case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
      x -> case unpackWord16X8# x of
        (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> case lane of { 0# -> word2Int# (word16ToWord# p0); 1# -> word2Int# (word16ToWord# p1); 2# -> word2Int# (word16ToWord# p2); 3# -> word2Int# (word16ToWord# p3); 4# -> word2Int# (word16ToWord# p4); 5# -> word2Int# (word16ToWord# p5); 6# -> word2Int# (word16ToWord# p6); _ -> word2Int# (word16ToWord# p7) }
  _ ->
    case unpackWord16X8# (broadcastWord16X8# (plusWord16# (wordToWord16# (int2Word# (a -# b))) (wordToWord16# 29##))) of
      (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> case lane of { 0# -> word2Int# (word16ToWord# p0); 1# -> word2Int# (word16ToWord# p1); 2# -> word2Int# (word16ToWord# p2); 3# -> word2Int# (word16ToWord# p3); 4# -> word2Int# (word16ToWord# p4); 5# -> word2Int# (word16ToWord# p5); 6# -> word2Int# (word16ToWord# p6); _ -> word2Int# (word16ToWord# p7) }

-- Residual helpers expose only machine scalars or eight unsigned scalar lanes.
{-# OPAQUE scalarWorker #-}
scalarWorker :: Int# -> Int# -> Int#
scalarWorker a b =
  case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
    x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
      y -> case unpackWord16X8# (plusWord16X8# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#) +# 31#

scalarHelperCase :: Int# -> Int# -> Int#
scalarHelperCase a b = case scalarWorker a b of result -> result +# 17#

{-# OPAQUE tupleWorker #-}
tupleWorker :: Int# -> Int# -> (# Word16#, Word16#, Word16#, Word16#, Word16#, Word16#, Word16#, Word16# #)
tupleWorker a b =
  case packWord16X8# (# wordToWord16# (int2Word# a), wordToWord16# (int2Word# b), wordToWord16# (int2Word# (a +# 1#)), wordToWord16# (int2Word# (b -# 1#)), wordToWord16# (int2Word# (a +# 32767#)), wordToWord16# (int2Word# (b -# 32768#)), wordToWord16# (int2Word# (a *# 3# +# 7#)), wordToWord16# (int2Word# (b *# 5# -# 11#)) #) of
    x -> case packWord16X8# (# wordToWord16# (int2Word# (b +# 2#)), wordToWord16# (int2Word# (a -# 3#)), wordToWord16# (int2Word# (b *# 7# +# 13#)), wordToWord16# (int2Word# (a *# 11# -# 17#)), wordToWord16# (int2Word# (b +# 32768#)), wordToWord16# (int2Word# (a -# 32767#)), wordToWord16# (int2Word# (b *# 13# +# 19#)), wordToWord16# (int2Word# (a *# 17# -# 23#)) #) of
      y -> unpackWord16X8# (timesWord16X8# x y)

tupleHelperCase :: Int# -> Int# -> Int#
tupleHelperCase a b = case tupleWorker a b of
  (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#)

-- Explicit negative control: vector formals are not admitted by this slice.
{-# OPAQUE vectorArgument #-}
vectorArgument :: Word16X8# -> Int#
vectorArgument value = case unpackWord16X8# value of
  (# p0, p1, p2, p3, p4, p5, p6, p7 #) -> (word2Int# (word16ToWord# p0) *# 3#) +# (word2Int# (word16ToWord# p1) *# 5#) +# (word2Int# (word16ToWord# p2) *# 7#) +# (word2Int# (word16ToWord# p3) *# 11#) +# (word2Int# (word16ToWord# p4) *# 13#) +# (word2Int# (word16ToWord# p5) *# 17#) +# (word2Int# (word16ToWord# p6) *# 19#) +# (word2Int# (word16ToWord# p7) *# 23#)
