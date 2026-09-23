{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdInt8X16 where

import GHC.Exts

-- Vectors remain local. Arithmetic is lane-wise modulo 256; every lane widens
-- before a distinct-weight machine-Int checksum, safe on the required 64-bit host.

plusCase :: Int# -> Int# -> Int#
plusCase a b =
  case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
    x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
      y -> case unpackInt8X16# (plusInt8X16# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)

minusCase :: Int# -> Int# -> Int#
minusCase a b =
  case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
    x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
      y -> case unpackInt8X16# (minusInt8X16# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)

timesCase :: Int# -> Int# -> Int#
timesCase a b =
  case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
    x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
      y -> case unpackInt8X16# (timesInt8X16# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)

negateCase :: Int# -> Int# -> Int#
negateCase a b =
  case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
    x -> case unpackInt8X16# (negateInt8X16# x) of
      (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)

packCase :: Int# -> Int# -> Int#
packCase a b =
  case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
    x -> case unpackInt8X16# (x) of
      (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)

broadcastCase :: Int# -> Int# -> Int#
broadcastCase a b =
  case unpackInt8X16# (broadcastInt8X16# (plusInt8# (intToInt8# (a -# b)) (intToInt8# 29#))) of
    (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)

-- Observe each operation and lane separately; each branch returns only Int#.
laneCase :: Int# -> Int# -> Int# -> Int# -> Int#
laneCase operation lane a b = case operation of
  0# ->
    case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
      x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
        y -> case unpackInt8X16# (plusInt8X16# x y) of
          (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> case lane of { 0# -> int8ToInt# p0; 1# -> int8ToInt# p1; 2# -> int8ToInt# p2; 3# -> int8ToInt# p3; 4# -> int8ToInt# p4; 5# -> int8ToInt# p5; 6# -> int8ToInt# p6; 7# -> int8ToInt# p7; 8# -> int8ToInt# p8; 9# -> int8ToInt# p9; 10# -> int8ToInt# p10; 11# -> int8ToInt# p11; 12# -> int8ToInt# p12; 13# -> int8ToInt# p13; 14# -> int8ToInt# p14; _ -> int8ToInt# p15 }
  1# ->
    case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
      x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
        y -> case unpackInt8X16# (minusInt8X16# x y) of
          (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> case lane of { 0# -> int8ToInt# p0; 1# -> int8ToInt# p1; 2# -> int8ToInt# p2; 3# -> int8ToInt# p3; 4# -> int8ToInt# p4; 5# -> int8ToInt# p5; 6# -> int8ToInt# p6; 7# -> int8ToInt# p7; 8# -> int8ToInt# p8; 9# -> int8ToInt# p9; 10# -> int8ToInt# p10; 11# -> int8ToInt# p11; 12# -> int8ToInt# p12; 13# -> int8ToInt# p13; 14# -> int8ToInt# p14; _ -> int8ToInt# p15 }
  2# ->
    case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
      x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
        y -> case unpackInt8X16# (timesInt8X16# x y) of
          (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> case lane of { 0# -> int8ToInt# p0; 1# -> int8ToInt# p1; 2# -> int8ToInt# p2; 3# -> int8ToInt# p3; 4# -> int8ToInt# p4; 5# -> int8ToInt# p5; 6# -> int8ToInt# p6; 7# -> int8ToInt# p7; 8# -> int8ToInt# p8; 9# -> int8ToInt# p9; 10# -> int8ToInt# p10; 11# -> int8ToInt# p11; 12# -> int8ToInt# p12; 13# -> int8ToInt# p13; 14# -> int8ToInt# p14; _ -> int8ToInt# p15 }
  3# ->
    case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
      x -> case unpackInt8X16# (negateInt8X16# x) of
        (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> case lane of { 0# -> int8ToInt# p0; 1# -> int8ToInt# p1; 2# -> int8ToInt# p2; 3# -> int8ToInt# p3; 4# -> int8ToInt# p4; 5# -> int8ToInt# p5; 6# -> int8ToInt# p6; 7# -> int8ToInt# p7; 8# -> int8ToInt# p8; 9# -> int8ToInt# p9; 10# -> int8ToInt# p10; 11# -> int8ToInt# p11; 12# -> int8ToInt# p12; 13# -> int8ToInt# p13; 14# -> int8ToInt# p14; _ -> int8ToInt# p15 }
  4# ->
    case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
      x -> case unpackInt8X16# (x) of
        (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> case lane of { 0# -> int8ToInt# p0; 1# -> int8ToInt# p1; 2# -> int8ToInt# p2; 3# -> int8ToInt# p3; 4# -> int8ToInt# p4; 5# -> int8ToInt# p5; 6# -> int8ToInt# p6; 7# -> int8ToInt# p7; 8# -> int8ToInt# p8; 9# -> int8ToInt# p9; 10# -> int8ToInt# p10; 11# -> int8ToInt# p11; 12# -> int8ToInt# p12; 13# -> int8ToInt# p13; 14# -> int8ToInt# p14; _ -> int8ToInt# p15 }
  _ ->
    case unpackInt8X16# (broadcastInt8X16# (plusInt8# (intToInt8# (a -# b)) (intToInt8# 29#))) of
      (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> case lane of { 0# -> int8ToInt# p0; 1# -> int8ToInt# p1; 2# -> int8ToInt# p2; 3# -> int8ToInt# p3; 4# -> int8ToInt# p4; 5# -> int8ToInt# p5; 6# -> int8ToInt# p6; 7# -> int8ToInt# p7; 8# -> int8ToInt# p8; 9# -> int8ToInt# p9; 10# -> int8ToInt# p10; 11# -> int8ToInt# p11; 12# -> int8ToInt# p12; 13# -> int8ToInt# p13; 14# -> int8ToInt# p14; _ -> int8ToInt# p15 }

-- Opaque residual controls expose only machine scalars or sixteen scalar lanes.
{-# OPAQUE scalarWorker #-}
scalarWorker :: Int# -> Int# -> Int#
scalarWorker a b =
  case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
    x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
      y -> case unpackInt8X16# (plusInt8X16# x y) of
        (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#) +# 31#

scalarHelperCase :: Int# -> Int# -> Int#
scalarHelperCase a b = case scalarWorker a b of result -> result +# 17#

{-# OPAQUE tupleWorker #-}
tupleWorker :: Int# -> Int# -> (# Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8#, Int8# #)
tupleWorker a b =
  case packInt8X16# (# intToInt8# a, intToInt8# b, intToInt8# (a +# 1#), intToInt8# (b -# 1#), intToInt8# (a +# 127#), intToInt8# (b -# 128#), intToInt8# (a *# 3# +# 7#), intToInt8# (b *# 5# -# 11#), intToInt8# (a *# 7# +# 29#), intToInt8# (b *# 9# -# 31#), intToInt8# (a *# 11# +# 37#), intToInt8# (b *# 13# -# 41#), intToInt8# (a *# 15# +# 43#), intToInt8# (b *# 17# -# 47#), intToInt8# (a *# 19# +# 53#), intToInt8# (b *# 21# -# 59#) #) of
    x -> case packInt8X16# (# intToInt8# (b +# 2#), intToInt8# (a -# 3#), intToInt8# (b *# 7# +# 13#), intToInt8# (a *# 11# -# 17#), intToInt8# (b +# 128#), intToInt8# (a -# 127#), intToInt8# (b *# 13# +# 19#), intToInt8# (a *# 17# -# 23#), intToInt8# (b *# 23# +# 61#), intToInt8# (a *# 25# -# 67#), intToInt8# (b *# 27# +# 71#), intToInt8# (a *# 29# -# 73#), intToInt8# (b *# 31# +# 79#), intToInt8# (a *# 33# -# 83#), intToInt8# (b *# 35# +# 89#), intToInt8# (a *# 37# -# 97#) #) of
      y -> unpackInt8X16# (timesInt8X16# x y)

tupleHelperCase :: Int# -> Int# -> Int#
tupleHelperCase a b = case tupleWorker a b of
  (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)

-- Explicit negative control: vector formals remain outside this bounded slice.
{-# OPAQUE vectorArgument #-}
vectorArgument :: Int8X16# -> Int#
vectorArgument value = case unpackInt8X16# value of
  (# p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10, p11, p12, p13, p14, p15 #) -> (int8ToInt# p0 *# 3#) +# (int8ToInt# p1 *# 5#) +# (int8ToInt# p2 *# 7#) +# (int8ToInt# p3 *# 11#) +# (int8ToInt# p4 *# 13#) +# (int8ToInt# p5 *# 17#) +# (int8ToInt# p6 *# 19#) +# (int8ToInt# p7 *# 23#) +# (int8ToInt# p8 *# 29#) +# (int8ToInt# p9 *# 31#) +# (int8ToInt# p10 *# 37#) +# (int8ToInt# p11 *# 41#) +# (int8ToInt# p12 *# 43#) +# (int8ToInt# p13 *# 47#) +# (int8ToInt# p14 *# 53#) +# (int8ToInt# p15 *# 59#)
