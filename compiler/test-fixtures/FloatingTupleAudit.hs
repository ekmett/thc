-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module FloatingTupleAudit where
import GHC.Exts
import Data.Complex

-- Ordinary public APIs produce real CPR workers with floating tuple results.
-- NOINLINE retains that boundary without preventing GHC's worker/wrapper pass.
{-# NOINLINE complexFloat #-}
complexFloat :: Float -> Complex Float
complexFloat x = conjugate ((x :+ (x + 1)) * ((x - 2) :+ 3))
{-# NOINLINE complexDouble #-}
complexDouble :: Double -> Complex Double
complexDouble x = conjugate ((x :+ (x + 1)) * ((x - 2) :+ 3))
complexFloatCase, complexDoubleCase :: Int# -> Int#
complexFloatCase n = case complexFloat (F# (int2Float# n)) of
  F# x :+ F# y -> float2Int# (plusFloat# x (timesFloat# y 3.0#))
complexDoubleCase n = case complexDouble (D# (int2Double# n)) of
  D# x :+ D# y -> double2Int# (x +## y *## 3.0##)

data Box = Box Int#
data Failure = Failure

-- The lifted leaf itself is bottom. Merely moving it must never force it.
{-# OPAQUE mixed #-}
mixed :: State# RealWorld -> Float# -> Double#
      -> (# State# RealWorld, (# Float#, (# #), Double# #), Box #)
mixed s x y = (# s, (# plusFloat# x 0.5#, (# #), y -## 0.25## #), raise# Failure #)
{-# OPAQUE mixedForward #-}
mixedForward :: State# RealWorld -> Float# -> Double#
             -> (# State# RealWorld, (# Float#, (# #), Double# #), Box #)
mixedForward s x y = case mixed s (plusFloat# x 1.0#) y of result -> result
mixedCase :: Int# -> Int#
mixedCase n =
  case mixedForward realWorld# (int2Float# n) (int2Double# n) of
    (# s1, (# a, _, b #), _ #) ->
      case mixed s1 (int2Float# (n +# 5#)) (int2Double# (n -# 7#)) of
        (# _, (# c, _, d #), _ #) ->
          float2Int# (timesFloat# a 2.0#) +# double2Int# (b *## 4.0##)
          +# float2Int# (timesFloat# c 6.0#) +# double2Int# (d *## 8.0##)

-- Retain a same-frame join whose tuple result has both floating widths.
{-# OPAQUE joined #-}
joined :: Int# -> (# Float#, Double# #)
joined x =
  let {-# NOINLINE finish #-}
      finish n = (# plusFloat# (int2Float# (n +# x)) 0.5#, int2Double# (n +# x) -## 0.25## #)
  in case x <=# 0# of
       1# -> finish (x -# 3#)
       _ -> finish (x +# 5#)
joinedCase :: Int# -> Int#
joinedCase n = case joined n of
  (# f, d #) -> float2Int# (timesFloat# f 2.0#) +# double2Int# (d *## 4.0##)

{-# OPAQUE ieeePair #-}
ieeePair :: Int# -> (# Float#, Double# #)
ieeePair n = case n of
  0# -> (# 0.0#, negateDouble# 0.0## #)
  1# -> (# negateFloat# 0.0#, 0.0## #)
  2# -> (# 1.401298464324817e-45#, 4.9406564584124654e-324## #)
  3# -> (# -1.401298464324817e-45#, -4.9406564584124654e-324## #)
  4# -> (# divideFloat# 1.0# 0.0#, (-1.0##) /## 0.0## #)
  5# -> (# divideFloat# (-1.0#) 0.0#, 1.0## /## 0.0## #)
  6# -> (# divideFloat# 0.0# 0.0#, 0.0## /## 0.0## #)
  _ -> (# 1.5#, -2.5## #)
ieeeCase :: Int# -> Int#
ieeeCase n = case ieeePair n of
  (# f, d #) -> neFloat# f f +# 2# *# (d /=## d)
    +# 4# *# gtFloat# (divideFloat# 1.0# f) 0.0#
    +# 8# *# ((1.0## /## d) <## 0.0##)
    +# 16# *# eqFloat# f 1.401298464324817e-45#
    +# 32# *# (d ==## (-4.9406564584124654e-324##))

-- Accepting result leaves must not silently widen aggregate argument support.
{-# OPAQUE floatingTupleArgument #-}
floatingTupleArgument :: (# Float#, Double# #) -> (# Float#, Double# #)
floatingTupleArgument x = x
