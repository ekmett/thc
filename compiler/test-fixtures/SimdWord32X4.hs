-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdWord32X4 where

import GHC.Exts

-- Vectors remain local. Lanes wrap modulo 2^32 and zero-extend before the
-- distinct-weight machine-Int checksum, safe on the required 64-bit host.

plusCase :: Int# -> Int# -> Int#
plusCase a b =
  case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
    x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
      y -> case unpackWord32X4# (plusWord32X4# x y) of
        (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#)

minusCase :: Int# -> Int# -> Int#
minusCase a b =
  case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
    x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
      y -> case unpackWord32X4# (minusWord32X4# x y) of
        (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#)

timesCase :: Int# -> Int# -> Int#
timesCase a b =
  case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
    x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
      y -> case unpackWord32X4# (timesWord32X4# x y) of
        (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#)

packCase :: Int# -> Int# -> Int#
packCase a b =
  case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
    x -> case unpackWord32X4# x of
      (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#)

broadcastCase :: Int# -> Int# -> Int#
broadcastCase a b =
  case unpackWord32X4# (broadcastWord32X4# (plusWord32# (wordToWord32# (int2Word# (a -# b))) (wordToWord32# 29##))) of
    (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#)

-- Every branch finishes its unsigned vector work and returns only Int#.
-- Operations 0..4 are plus, minus, times, pack, broadcast. There is no negate.
laneCase :: Int# -> Int# -> Int# -> Int# -> Int#
laneCase operation lane a b = case operation of
  0# ->
    case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
      x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
        y -> case unpackWord32X4# (plusWord32X4# x y) of
          (# p0, p1, p2, p3 #) -> case lane of { 0# -> word2Int# (word32ToWord# p0); 1# -> word2Int# (word32ToWord# p1); 2# -> word2Int# (word32ToWord# p2); _ -> word2Int# (word32ToWord# p3) }
  1# ->
    case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
      x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
        y -> case unpackWord32X4# (minusWord32X4# x y) of
          (# p0, p1, p2, p3 #) -> case lane of { 0# -> word2Int# (word32ToWord# p0); 1# -> word2Int# (word32ToWord# p1); 2# -> word2Int# (word32ToWord# p2); _ -> word2Int# (word32ToWord# p3) }
  2# ->
    case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
      x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
        y -> case unpackWord32X4# (timesWord32X4# x y) of
          (# p0, p1, p2, p3 #) -> case lane of { 0# -> word2Int# (word32ToWord# p0); 1# -> word2Int# (word32ToWord# p1); 2# -> word2Int# (word32ToWord# p2); _ -> word2Int# (word32ToWord# p3) }
  3# ->
    case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
      x -> case unpackWord32X4# x of
        (# p0, p1, p2, p3 #) -> case lane of { 0# -> word2Int# (word32ToWord# p0); 1# -> word2Int# (word32ToWord# p1); 2# -> word2Int# (word32ToWord# p2); _ -> word2Int# (word32ToWord# p3) }
  _ ->
    case unpackWord32X4# (broadcastWord32X4# (plusWord32# (wordToWord32# (int2Word# (a -# b))) (wordToWord32# 29##))) of
      (# p0, p1, p2, p3 #) -> case lane of { 0# -> word2Int# (word32ToWord# p0); 1# -> word2Int# (word32ToWord# p1); 2# -> word2Int# (word32ToWord# p2); _ -> word2Int# (word32ToWord# p3) }

-- Residual helpers expose only machine scalars or four unsigned scalar lanes.
{-# OPAQUE scalarWorker #-}
scalarWorker :: Int# -> Int# -> Int#
scalarWorker a b =
  case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
    x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
      y -> case unpackWord32X4# (plusWord32X4# x y) of
        (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#) +# 31#

scalarHelperCase :: Int# -> Int# -> Int#
scalarHelperCase a b = case scalarWorker a b of result -> result +# 17#

{-# OPAQUE tupleWorker #-}
tupleWorker :: Int# -> Int# -> (# Word32#, Word32#, Word32#, Word32# #)
tupleWorker a b =
  case packWord32X4# (# wordToWord32# (int2Word# a), wordToWord32# (int2Word# b), wordToWord32# (int2Word# (a +# 1#)), wordToWord32# (int2Word# (b -# 1#)) #) of
    x -> case packWord32X4# (# wordToWord32# (int2Word# (b +# 2#)), wordToWord32# (int2Word# (a -# 3#)), wordToWord32# (int2Word# (b *# 7# +# 13#)), wordToWord32# (int2Word# (a *# 11# -# 17#)) #) of
      y -> unpackWord32X4# (timesWord32X4# x y)

tupleHelperCase :: Int# -> Int# -> Int#
tupleHelperCase a b = case tupleWorker a b of
  (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#)

-- Explicit negative control: vector formals are not admitted by this slice.
{-# OPAQUE vectorArgument #-}
vectorArgument :: Word32X4# -> Int#
vectorArgument value = case unpackWord32X4# value of
  (# p0, p1, p2, p3 #) -> (word2Int# (word32ToWord# p0) *# 3#) +# (word2Int# (word32ToWord# p1) *# 5#) +# (word2Int# (word32ToWord# p2) *# 7#) +# (word2Int# (word32ToWord# p3) *# 11#)
