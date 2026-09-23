{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdDoubleX2 where
import GHC.Exts

-- Scalar host inputs; vectors stay local. Both lanes affect these signatures.
checksum :: Double# -> Double# -> Int#
checksum a b = (double2Int# (a *## 4.0##) *# 7#) `xorI#`
               (double2Int# (b *## 4.0##) *# 11#)

plusCase, minusCase, timesCase :: Int# -> Int# -> Int#
plusCase a b = case packDoubleX2# (# int2Double# a, int2Double# b #) of
  x -> case unpackDoubleX2# (plusDoubleX2# x (broadcastDoubleX2# 0.5##)) of
    (# p,q #) -> checksum p q
minusCase a b = case packDoubleX2# (# int2Double# a, int2Double# b #) of
  x -> case unpackDoubleX2# (minusDoubleX2# x (broadcastDoubleX2# 0.25##)) of
    (# p,q #) -> checksum p q
timesCase a b = case packDoubleX2# (# int2Double# a, int2Double# b #) of
  x -> case unpackDoubleX2# (timesDoubleX2# x (packDoubleX2# (# 0.5##,-2.0## #))) of
    (# p,q #) -> checksum p q

edgeValue :: Int# -> Double#
edgeValue n = case n of
  0# -> 0.0##
  1# -> negateDouble# 0.0##
  2# -> 4.9406564584124654e-324##
  3# -> -4.9406564584124654e-324##
  4# -> 2.2250738585072009e-308##
  5# -> -2.2250738585072009e-308##
  6# -> 2.2250738585072014e-308##
  7# -> -2.2250738585072014e-308##
  8# -> 1.7976931348623157e308##
  9# -> -1.7976931348623157e308##
  10# -> 1.0## /## 0.0##
  11# -> (-1.0##) /## 0.0##
  12# -> 0.0## /## 0.0##
  13# -> 0.5##
  14# -> -0.5##
  15# -> 1.0##
  16# -> -1.0##
  17# -> 9007199254740992.0##
  18# -> 9007199254740994.0##
  19# -> 1.4821969375237396e-323##
  _ -> -1.4821969375237396e-323##

move0, move1 :: Int# -> Int# -> Double#
move0 a b = case unpackDoubleX2# (packDoubleX2# (# edgeValue a,edgeValue b #)) of
  (# p,_ #) -> p
move1 a b = case unpackDoubleX2# (packDoubleX2# (# edgeValue a,edgeValue b #)) of
  (# _,q #) -> q
broadcastCase :: Int# -> Double#
broadcastCase a = case unpackDoubleX2# (broadcastDoubleX2# (edgeValue a)) of
  (# _,q #) -> q

-- Native driver bitcasts these scalar results outside exported guest code.
-- The two lane projections check exact finite bits and exceptional classes.
edgePlus0, edgePlus1, edgeMinus0, edgeMinus1, edgeTimes0, edgeTimes1 :: Int# -> Int# -> Int# -> Double#
edgePlus0 a b c = case packDoubleX2# (# edgeValue a,edgeValue b #) of
  x -> case unpackDoubleX2# (plusDoubleX2# x (broadcastDoubleX2# (edgeValue c))) of (# p,_ #) -> p
edgePlus1 a b c = case packDoubleX2# (# edgeValue a,edgeValue b #) of
  x -> case unpackDoubleX2# (plusDoubleX2# x (broadcastDoubleX2# (edgeValue c))) of (# _,q #) -> q
edgeMinus0 a b c = case packDoubleX2# (# edgeValue a,edgeValue b #) of
  x -> case unpackDoubleX2# (minusDoubleX2# x (broadcastDoubleX2# (edgeValue c))) of (# p,_ #) -> p
edgeMinus1 a b c = case packDoubleX2# (# edgeValue a,edgeValue b #) of
  x -> case unpackDoubleX2# (minusDoubleX2# x (broadcastDoubleX2# (edgeValue c))) of (# _,q #) -> q
edgeTimes0 a b c = case packDoubleX2# (# edgeValue a,edgeValue b #) of
  x -> case unpackDoubleX2# (timesDoubleX2# x (broadcastDoubleX2# (edgeValue c))) of (# p,_ #) -> p
edgeTimes1 a b c = case packDoubleX2# (# edgeValue a,edgeValue b #) of
  x -> case unpackDoubleX2# (timesDoubleX2# x (broadcastDoubleX2# (edgeValue c))) of (# _,q #) -> q

-- n=0 requires +0 after two roundings; FMA would produce -2^-104.
nonFmaCase :: Int# -> Double#
nonFmaCase n =
  case broadcastDoubleX2# (int2Double# (andI# n 1#) +## 1.0000000000000002220446049250313080847263336181640625##) of
    x -> case timesDoubleX2# x (broadcastDoubleX2# 0.9999999999999997779553950749686919152736663818359375##) of
      y -> case unpackDoubleX2# (plusDoubleX2# y (broadcastDoubleX2# (-1.0##))) of (# p,_ #) -> p

-- Genuine vector ABI control: this remains unsupported by THC.
vectorArgument :: DoubleX2# -> DoubleX2#
vectorArgument x = plusDoubleX2# x (broadcastDoubleX2# 0.5##)
