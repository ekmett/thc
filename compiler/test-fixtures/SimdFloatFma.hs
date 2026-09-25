-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdFloatFma where
import GHC.Exts
import GHC.Prim (fmaddFloatX4#, fmsubFloatX4#, fnmaddFloatX4#, fnmsubFloatX4#)

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
