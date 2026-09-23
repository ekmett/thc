{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdInt16X8 where

import GHC.Exts

-- Vectors remain local. Arithmetic is lane-wise modulo 2^16; each lane widens
-- before a distinct-weight checksum whose magnitude is below 2^22.

plusCase :: Int# -> Int# -> Int#
plusCase a b =
  case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
    x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
      y -> case unpackInt16X8# (plusInt16X8# x y) of
        (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)

minusCase :: Int# -> Int# -> Int#
minusCase a b =
  case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
    x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
      y -> case unpackInt16X8# (minusInt16X8# x y) of
        (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)

timesCase :: Int# -> Int# -> Int#
timesCase a b =
  case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
    x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
      y -> case unpackInt16X8# (timesInt16X8# x y) of
        (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)

negateCase :: Int# -> Int# -> Int#
negateCase a b =
  case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
    x -> case unpackInt16X8# (negateInt16X8# x) of
        (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)

packCase :: Int# -> Int# -> Int#
packCase a b =
  case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
    x -> case unpackInt16X8# (x) of
        (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)

broadcastCase :: Int# -> Int# -> Int#
broadcastCase a b =
  case unpackInt16X8# (broadcastInt16X8# (intToInt16# (a -# b +# 29#))) of
        (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)

-- Observe every lane separately for every operation; only scalar results cross
-- the case boundary. No vector arguments, closures, heap or tuple fields.
laneCase :: Int# -> Int# -> Int# -> Int# -> Int#
laneCase operation lane a b = case operation of
  0# ->
    case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
      x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
        y -> case unpackInt16X8# (plusInt16X8# x y) of
          (# p, q, r, s, t, u, v, w #) -> case lane of { 0# -> int16ToInt# p; 1# -> int16ToInt# q; 2# -> int16ToInt# r; 3# -> int16ToInt# s; 4# -> int16ToInt# t; 5# -> int16ToInt# u; 6# -> int16ToInt# v; _ -> int16ToInt# w }
  1# ->
    case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
      x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
        y -> case unpackInt16X8# (minusInt16X8# x y) of
          (# p, q, r, s, t, u, v, w #) -> case lane of { 0# -> int16ToInt# p; 1# -> int16ToInt# q; 2# -> int16ToInt# r; 3# -> int16ToInt# s; 4# -> int16ToInt# t; 5# -> int16ToInt# u; 6# -> int16ToInt# v; _ -> int16ToInt# w }
  2# ->
    case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
      x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
        y -> case unpackInt16X8# (timesInt16X8# x y) of
          (# p, q, r, s, t, u, v, w #) -> case lane of { 0# -> int16ToInt# p; 1# -> int16ToInt# q; 2# -> int16ToInt# r; 3# -> int16ToInt# s; 4# -> int16ToInt# t; 5# -> int16ToInt# u; 6# -> int16ToInt# v; _ -> int16ToInt# w }
  3# ->
    case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
      x -> case unpackInt16X8# (negateInt16X8# x) of
          (# p, q, r, s, t, u, v, w #) -> case lane of { 0# -> int16ToInt# p; 1# -> int16ToInt# q; 2# -> int16ToInt# r; 3# -> int16ToInt# s; 4# -> int16ToInt# t; 5# -> int16ToInt# u; 6# -> int16ToInt# v; _ -> int16ToInt# w }
  4# ->
    case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
      x -> case unpackInt16X8# (x) of
          (# p, q, r, s, t, u, v, w #) -> case lane of { 0# -> int16ToInt# p; 1# -> int16ToInt# q; 2# -> int16ToInt# r; 3# -> int16ToInt# s; 4# -> int16ToInt# t; 5# -> int16ToInt# u; 6# -> int16ToInt# v; _ -> int16ToInt# w }
  _ ->
    case unpackInt16X8# (broadcastInt16X8# (intToInt16# (a -# b +# 29#))) of
          (# p, q, r, s, t, u, v, w #) -> case lane of { 0# -> int16ToInt# p; 1# -> int16ToInt# q; 2# -> int16ToInt# r; 3# -> int16ToInt# s; 4# -> int16ToInt# t; 5# -> int16ToInt# u; 6# -> int16ToInt# v; _ -> int16ToInt# w }

-- Residual controls retain only scalar arguments/results or a scalar-lane
-- tuple result; the vector carrier itself never crosses a function boundary.
{-# OPAQUE scalarWorker #-}
scalarWorker :: Int# -> Int# -> Int#
scalarWorker a b =
  case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
    x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
      y -> case unpackInt16X8# (plusInt16X8# x y) of
        (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#) +# 31#

scalarHelperCase :: Int# -> Int# -> Int#
scalarHelperCase a b = case scalarWorker a b of result -> result +# 17#

{-# OPAQUE tupleWorker #-}
tupleWorker :: Int# -> Int# -> (# Int16#, Int16#, Int16#, Int16#, Int16#, Int16#, Int16#, Int16# #)
tupleWorker a b =
  case packInt16X8# (# intToInt16# a, intToInt16# b, intToInt16# (a +# 1#), intToInt16# (b -# 1#), intToInt16# (a +# 32767#), intToInt16# (b -# 32768#), intToInt16# (a *# 3# +# 7#), intToInt16# (b *# 5# -# 11#) #) of
    x -> case packInt16X8# (# intToInt16# (b +# 2#), intToInt16# (a -# 3#), intToInt16# (b *# 7# +# 13#), intToInt16# (a *# 11# -# 17#), intToInt16# (b +# 32768#), intToInt16# (a -# 32767#), intToInt16# (b *# 13# +# 19#), intToInt16# (a *# 17# -# 23#) #) of
      y -> unpackInt16X8# (timesInt16X8# x y)

tupleHelperCase :: Int# -> Int# -> Int#
tupleHelperCase a b = case tupleWorker a b of
  (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)

-- Explicit negative control: vector formals are not admitted by this slice.
{-# OPAQUE vectorArgument #-}
vectorArgument :: Int16X8# -> Int#
vectorArgument value = case unpackInt16X8# value of
  (# p, q, r, s, t, u, v, w #) -> (int16ToInt# p *# 3#) +# (int16ToInt# q *# 5#) +# (int16ToInt# r *# 7#) +# (int16ToInt# s *# 11#) +# (int16ToInt# t *# 13#) +# (int16ToInt# u *# 17#) +# (int16ToInt# v *# 19#) +# (int16ToInt# w *# 23#)
