-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module FloatingAudit where

import GHC.Exts

-- Keep a typed residual boundary for all eight genuinely fused operations.
{-# NOINLINE fusedFloatAddWorker #-}
{-# NOINLINE fusedFloatSubWorker #-}
{-# NOINLINE fusedFloatNegAddWorker #-}
{-# NOINLINE fusedFloatNegSubWorker #-}
fusedFloatAddWorker, fusedFloatSubWorker, fusedFloatNegAddWorker, fusedFloatNegSubWorker :: Float# -> Float# -> Float# -> Float#
fusedFloatAddWorker x y z = fmaddFloat# x y z
fusedFloatSubWorker x y z = fmsubFloat# x y z
fusedFloatNegAddWorker x y z = fnmaddFloat# x y z
fusedFloatNegSubWorker x y z = fnmsubFloat# x y z
{-# NOINLINE fusedDoubleAddWorker #-}
{-# NOINLINE fusedDoubleSubWorker #-}
{-# NOINLINE fusedDoubleNegAddWorker #-}
{-# NOINLINE fusedDoubleNegSubWorker #-}
fusedDoubleAddWorker, fusedDoubleSubWorker, fusedDoubleNegAddWorker, fusedDoubleNegSubWorker :: Double# -> Double# -> Double# -> Double#
fusedDoubleAddWorker x y z = fmaddDouble# x y z
fusedDoubleSubWorker x y z = fmsubDouble# x y z
fusedDoubleNegAddWorker x y z = fnmaddDouble# x y z
fusedDoubleNegSubWorker x y z = fnmsubDouble# x y z

{-# INLINE fusedFloatBits #-}
fusedFloatBits :: (Float# -> Float# -> Float# -> Float#) -> Word# -> Word# -> Word# -> Word#
fusedFloatBits f x y z = word32ToWord# (castFloatToWord32#
  (f (castWord32ToFloat# (wordToWord32# x)) (castWord32ToFloat# (wordToWord32# y)) (castWord32ToFloat# (wordToWord32# z))))
{-# INLINE fusedDoubleBits #-}
fusedDoubleBits :: (Double# -> Double# -> Double# -> Double#) -> Word# -> Word# -> Word# -> Word#
fusedDoubleBits f x y z = word64ToWord# (castDoubleToWord64#
  (f (castWord64ToDouble# (wordToWord64# x)) (castWord64ToDouble# (wordToWord64# y)) (castWord64ToDouble# (wordToWord64# z))))
fusedFloatAdd, fusedFloatSub, fusedFloatNegAdd, fusedFloatNegSub :: Word# -> Word# -> Word# -> Word#
fusedFloatAdd x y z = fusedFloatBits fusedFloatAddWorker x y z
fusedFloatSub x y z = fusedFloatBits fusedFloatSubWorker x y z
fusedFloatNegAdd x y z = fusedFloatBits fusedFloatNegAddWorker x y z
fusedFloatNegSub x y z = fusedFloatBits fusedFloatNegSubWorker x y z
fusedDoubleAdd, fusedDoubleSub, fusedDoubleNegAdd, fusedDoubleNegSub :: Word# -> Word# -> Word# -> Word#
fusedDoubleAdd x y z = fusedDoubleBits fusedDoubleAddWorker x y z
fusedDoubleSub x y z = fusedDoubleBits fusedDoubleSubWorker x y z
fusedDoubleNegAdd x y z = fusedDoubleBits fusedDoubleNegAddWorker x y z
fusedDoubleNegSub x y z = fusedDoubleBits fusedDoubleNegSubWorker x y z

-- These opaque-to-the-caller boundaries are conformance fixtures: keep actual
-- scalar floating arguments/results and constructor fields in optimized Core.
{-# NOINLINE floatWorker #-}
floatWorker :: Float# -> Float# -> Float#
floatWorker x y = divideFloat# (minusFloat# (timesFloat# x y) (negateFloat# x))
                             (plusFloat# y 1.0#)

{-# NOINLINE doubleWorker #-}
doubleWorker :: Double# -> Double# -> Double#
doubleWorker x y = ((x *## y) -## negateDouble# x) /## (y +## 1.0##)

floatArithmetic, doubleArithmetic, floatRounding, doubleRounding :: Int# -> Int#
floatArithmetic n = float2Int# (floatWorker (int2Float# n) 2.5#)
doubleArithmetic n = double2Int# (doubleWorker (int2Double# n) 2.5##)
floatRounding n = float2Int# (plusFloat# (int2Float# n) 1.0#)
doubleRounding n = double2Int# (int2Double# n +## 1.0##)

-- Conversion operands stay finite and inside Int's representable range.
floatDoubleConversion :: Int# -> Int#
floatDoubleConversion n = double2Int# (float2Double# (int2Float# n))
doubleFloatConversion :: Int# -> Int#
doubleFloatConversion n = float2Int# (double2Float# (int2Double# n))

floatComparisons, doubleComparisons :: Int# -> Int#
floatComparisons n =
  let x = case andI# n 7# of
        0# -> divideFloat# 0.0# 0.0#
        1# -> divideFloat# 1.0# 0.0#
        2# -> divideFloat# (-1.0#) 0.0#
        3# -> negateFloat# 0.0#
        4# -> 0.0#
        5# -> 1.401298464324817e-45#
        6# -> -1.401298464324817e-45#
        _ -> int2Float# n
      y = negateFloat# x
  in eqFloat# x y +# 2# *# neFloat# x y +# 4# *# ltFloat# x y
     +# 8# *# leFloat# x y +# 16# *# gtFloat# x y +# 32# *# geFloat# x y
doubleComparisons n =
  let x = case andI# n 7# of
        0# -> 0.0## /## 0.0##
        1# -> 1.0## /## 0.0##
        2# -> (-1.0##) /## 0.0##
        3# -> negateDouble# 0.0##
        4# -> 0.0##
        5# -> 4.9406564584124654e-324##
        6# -> -4.9406564584124654e-324##
        _ -> int2Double# n
      y = negateDouble# x
  in (x ==## y) +# 2# *# (x /=## y) +# 4# *# (x <## y)
     +# 8# *# (x <=## y) +# 16# *# (x >## y) +# 32# *# (x >=## y)

floatSignedZero, doubleSignedZero :: Int# -> Int#
floatSignedZero n = gtFloat# (divideFloat# 1.0# (timesFloat# (int2Float# n) 0.0#)) 0.0#
doubleSignedZero n = (1.0## /## (int2Double# n *## 0.0##)) >## 0.0##

data FloatingBox = FloatingBox Float# Double#
-- OPAQUE prevents CPR from replacing this boxed-field control with a tuple.
{-# OPAQUE floatingBox #-}
floatingBox :: Float# -> Double# -> FloatingBox
floatingBox x y = FloatingBox x y
floatingFields :: Int# -> Int#
floatingFields n = case floatingBox (int2Float# n) (int2Double# n +## 0.5##) of
  FloatingBox x y -> float2Int# x +# double2Int# (y *## 2.0##)

-- Preserve the original frontier's genuine CPR boundary as a positive control.
-- GHC turns this NOINLINE boxed producer into a floating tuple-return worker.
{-# NOINLINE floatingCprBox #-}
floatingCprBox :: Float# -> Double# -> FloatingBox
floatingCprBox x y = FloatingBox x y
floatingTupleFrontier :: Int# -> Int#
floatingTupleFrontier n = case floatingCprBox (int2Float# n) (int2Double# n) of
  FloatingBox x y -> float2Int# x +# double2Int# y

{-# NOINLINE applyFloatingClosure #-}
applyFloatingClosure :: (Int# -> Int#) -> Int# -> Int#
applyFloatingClosure f n = f n
floatingCaptures :: Int# -> Int#
floatingCaptures n =
  case floatingBox (int2Float# n) (int2Double# n) of
    FloatingBox x y ->
      applyFloatingClosure (\k -> float2Int# (plusFloat# x (int2Float# k))
                              +# double2Int# (y -## int2Double# k)) 3#

-- No call fence here: this loop checks typed frame moves and inlined arithmetic.
floatingLoop :: Int# -> Int#
floatingLoop n = go (andI# n 31#) 0.0# 0.0## where
  go k x y = case k <=# 0# of
    1# -> float2Int# x +# double2Int# y
    _ -> go (k -# 1#) (plusFloat# x 0.5#) (y +## 0.25##)

-- Capturing n keeps this recurrence local. Both floating pairs are permuted;
-- a sequential formal overwrite would corrupt the next iteration.
floatingJoinSwap :: Int# -> Int#
floatingJoinSwap n =
  let go k x xx y yy = case k <=# 0# of
        1# -> n +# float2Int# (plusFloat# x (timesFloat# xx 3.0#))
                +# double2Int# (y +## yy *## 5.0##)
        _ -> go (k -# 1#) (plusFloat# xx 0.5#) x (yy +## 0.25##) y
  in go (andI# n 7#) 1.0# 2.0# 3.0## 4.0##
