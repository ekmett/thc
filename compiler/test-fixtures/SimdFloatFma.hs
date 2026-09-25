-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdFloatFma where
import GHC.Exts
import GHC.Prim (fmaddFloatX4#, fmsubFloatX4#, fnmaddFloatX4#, fnmsubFloatX4#,
                 fmaddDoubleX2#, fmsubDoubleX2#, fnmaddDoubleX2#, fnmsubDoubleX2#)

-- GHC 902339d332fb4ce2b3c87dcac1ee6495d41ad886 primops.txt.pp:4289-4308:
-- x*y+z, x*y-z, -x*y+z, -x*y-z. Keep real vector call/return boundaries.
{-# NOINLINE addWorker #-}
{-# NOINLINE subWorker #-}
{-# NOINLINE negAddWorker #-}
{-# NOINLINE negSubWorker #-}
addWorker, subWorker, negAddWorker, negSubWorker :: FloatX4# -> FloatX4# -> FloatX4# -> FloatX4#
addWorker x y z = fmaddFloatX4# x y z
subWorker x y z = fmsubFloatX4# x y z
negAddWorker x y z = fnmaddFloatX4# x y z
negSubWorker x y z = fnmsubFloatX4# x y z

{-# INLINE laneBits #-}
laneBits :: (FloatX4# -> FloatX4# -> FloatX4# -> FloatX4#) -> Word# -> Word# -> Word# -> Int# -> Word#
laneBits operation xb yb zb lane =
  case castWord32ToFloat# (wordToWord32# xb) of { x ->
  case castWord32ToFloat# (wordToWord32# yb) of { y ->
  case castWord32ToFloat# (wordToWord32# zb) of { z ->
  case operation (packFloatX4# (# x, y, z, x #))
                 (packFloatX4# (# y, z, x, y #))
                 (packFloatX4# (# z, x, y, negateFloat# z #)) of { vector ->
  case unpackFloatX4# vector of { (# a, b, c, d #) ->
  word32ToWord# (castFloatToWord32# (case lane of
    0# -> a; 1# -> b; 2# -> c; _ -> d)) } } } } }

addCase, subCase, negAddCase, negSubCase :: Word# -> Word# -> Word# -> Int# -> Word#
addCase = laneBits addWorker
subCase = laneBits subWorker
negAddCase = laneBits negAddWorker
negSubCase = laneBits negSubWorker

-- Share the exporter and native executable with FloatX4. These are distinct
-- exact DoubleX2 call boundaries, not a conversion through the Float carrier.
{-# NOINLINE doubleAddWorker #-}
{-# NOINLINE doubleSubWorker #-}
{-# NOINLINE doubleNegAddWorker #-}
{-# NOINLINE doubleNegSubWorker #-}
doubleAddWorker, doubleSubWorker, doubleNegAddWorker, doubleNegSubWorker :: DoubleX2# -> DoubleX2# -> DoubleX2# -> DoubleX2#
doubleAddWorker x y z = fmaddDoubleX2# x y z
doubleSubWorker x y z = fmsubDoubleX2# x y z
doubleNegAddWorker x y z = fnmaddDoubleX2# x y z
doubleNegSubWorker x y z = fnmsubDoubleX2# x y z

{-# INLINE doubleLaneBits #-}
doubleLaneBits :: (DoubleX2# -> DoubleX2# -> DoubleX2# -> DoubleX2#) -> Word# -> Word# -> Word# -> Int# -> Word#
doubleLaneBits operation xb yb zb lane =
  case castWord64ToDouble# (wordToWord64# xb) of { x ->
  case castWord64ToDouble# (wordToWord64# yb) of { y ->
  case castWord64ToDouble# (wordToWord64# zb) of { z ->
  case operation (packDoubleX2# (# x, y #))
                 (packDoubleX2# (# y, z #))
                 (packDoubleX2# (# z, negateDouble# x #)) of { vector ->
  case unpackDoubleX2# vector of { (# a, b #) ->
  word64ToWord# (castDoubleToWord64# (case lane of 0# -> a; _ -> b)) } } } } }

doubleAddCase, doubleSubCase, doubleNegAddCase, doubleNegSubCase :: Word# -> Word# -> Word# -> Int# -> Word#
doubleAddCase = doubleLaneBits doubleAddWorker
doubleSubCase = doubleLaneBits doubleSubWorker
doubleNegAddCase = doubleLaneBits doubleNegAddWorker
doubleNegSubCase = doubleLaneBits doubleNegSubWorker
