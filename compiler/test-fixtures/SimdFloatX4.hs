-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdFloatX4 where
import GHC.Exts

-- Scalar host entries; genuine vectors remain local. No optimizer fences.
-- The native corpus bounds these conversions to finite representable Ints.
checksum :: Float# -> Float# -> Float# -> Float# -> Int#
checksum a b c d =
  (float2Int# (timesFloat# a 4.0#) *# 7#) `xorI#`
  (float2Int# (timesFloat# b 4.0#) *# 11#) `xorI#`
  (float2Int# (timesFloat# c 4.0#) *# 13#) `xorI#`
  (float2Int# (timesFloat# d 4.0#) *# 17#)

plusCase, minusCase, timesCase :: Int# -> Int# -> Int# -> Int# -> Int#
plusCase a b c d =
  case packFloatX4# (# int2Float# a, int2Float# b, int2Float# c, int2Float# d #) of
    x -> case unpackFloatX4# (plusFloatX4# x (broadcastFloatX4# 1.0#)) of
      (# p, q, r, s #) -> checksum p q r s
minusCase a b c d =
  case packFloatX4# (# int2Float# a, int2Float# b, int2Float# c, int2Float# d #) of
    x -> case unpackFloatX4# (minusFloatX4# x (broadcastFloatX4# 0.5#)) of
      (# p, q, r, s #) -> checksum p q r s
timesCase a b c d =
  case packFloatX4# (# int2Float# a, int2Float# b, int2Float# c, int2Float# d #) of
    x -> case packFloatX4# (# 0.5#, -2.0#, 1.5#, -0.25# #) of
      y -> case unpackFloatX4# (timesFloatX4# x y) of
        (# p, q, r, s #) -> checksum p q r s

edgeValue :: Int# -> Float#
edgeValue n = case n of
  0# -> 0.0#
  1# -> negateFloat# 0.0#
  2# -> 1.401298464324817e-45#
  3# -> -1.401298464324817e-45#
  4# -> 1.1754942106924411e-38#
  5# -> -1.1754942106924411e-38#
  6# -> 1.1754943508222875e-38#
  7# -> -1.1754943508222875e-38#
  8# -> 3.4028234663852886e38#
  9# -> -3.4028234663852886e38#
  10# -> divideFloat# 1.0# 0.0#
  11# -> divideFloat# (-1.0#) 0.0#
  12# -> divideFloat# 0.0# 0.0#
  13# -> 0.5#
  14# -> -0.5#
  15# -> 1.0#
  16# -> -1.0#
  17# -> 16777216.0#
  18# -> 16777218.0#
  19# -> 4.203895392974451e-45#
  _ -> -4.203895392974451e-45#

-- Five bits per lane, not a commutative reduction. NaN payload/sign is
-- intentionally unspecified. Dedicated codes distinguish subnormal rounding
-- to two minimum subnormals and ties rounding to +/-16777220 exactly.
classify :: Float# -> Int#
classify x = case neFloat# x x of
  1# -> 0#
  _ -> case eqFloat# x 0.0# of
    1# -> case ltFloat# (divideFloat# 1.0# x) 0.0# of
      1# -> 2#
      _ -> 1#
    _ -> case eqFloat# x (divideFloat# 1.0# 0.0#) of
      1# -> 3#
      _ -> case eqFloat# x (divideFloat# (-1.0#) 0.0#) of
        1# -> 4#
        _ -> case eqFloat# x 1.401298464324817e-45# of
          1# -> 5#
          _ -> case eqFloat# x (-1.401298464324817e-45#) of
            1# -> 6#
            _ -> case eqFloat# x 1.1754942106924411e-38# of
              1# -> 7#
              _ -> case eqFloat# x (-1.1754942106924411e-38#) of
                1# -> 8#
                _ -> case eqFloat# x 1.1754943508222875e-38# of
                  1# -> 9#
                  _ -> case eqFloat# x (-1.1754943508222875e-38#) of
                    1# -> 10#
                    _ -> case eqFloat# x 3.4028234663852886e38# of
                      1# -> 11#
                      _ -> case eqFloat# x (-3.4028234663852886e38#) of
                        1# -> 12#
                        _ -> case eqFloat# x 2.802596928649634e-45# of
                          1# -> 15#
                          _ -> case eqFloat# x (-2.802596928649634e-45#) of
                            1# -> 16#
                            _ -> case eqFloat# x 16777220.0# of
                              1# -> 17#
                              _ -> case eqFloat# x (-16777220.0#) of
                                1# -> 18#
                                _ -> case ltFloat# x 0.0# of
                                  1# -> 13#
                                  _ -> 14#

classes :: Float# -> Float# -> Float# -> Float# -> Int#
classes a b c d = classify a +# 32# *# classify b +# 1024# *# classify c +# 32768# *# classify d

edgePlus, edgeMinus, edgeTimes :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
edgePlus a b c d e =
  case packFloatX4# (# edgeValue a, edgeValue b, edgeValue c, edgeValue d #) of
    x -> case unpackFloatX4# (plusFloatX4# x (broadcastFloatX4# (edgeValue e))) of
      (# p, q, r, s #) -> classes p q r s
edgeMinus a b c d e =
  case packFloatX4# (# edgeValue a, edgeValue b, edgeValue c, edgeValue d #) of
    x -> case unpackFloatX4# (minusFloatX4# x (broadcastFloatX4# (edgeValue e))) of
      (# p, q, r, s #) -> classes p q r s
edgeTimes a b c d e =
  case packFloatX4# (# edgeValue a, edgeValue b, edgeValue c, edgeValue d #) of
    x -> case unpackFloatX4# (timesFloatX4# x (broadcastFloatX4# (edgeValue e))) of
      (# p, q, r, s #) -> classes p q r s

-- n=0: (1+2^-23)*(1-2^-23)-1 must be +0 after TWO roundings;
-- a fused operation would instead produce the negative finite value -2^-46.
nonFmaCase :: Int# -> Int#
nonFmaCase n =
  case broadcastFloatX4# (plusFloat# (int2Float# (andI# n 1#)) 1.00000011920928955078125#) of
    x -> case timesFloatX4# x (broadcastFloatX4# 0.99999988079071044921875#) of
      y -> case unpackFloatX4# (plusFloatX4# y (broadcastFloatX4# (-1.0#))) of
        (# p, q, r, s #) -> classes p q r s

-- Metadata/frontier control: local vector support must not accept a vector ABI.
vectorArgument :: FloatX4# -> FloatX4#
vectorArgument x = plusFloatX4# x (broadcastFloatX4# 0.5#)
