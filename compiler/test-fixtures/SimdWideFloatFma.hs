-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SimdWideFloatFma where
import GHC.Exts
import GHC.Prim (fmaddFloatX16#, fmsubFloatX16#, fnmaddFloatX16#, fnmsubFloatX16#,
                 fmaddDoubleX8#, fmsubDoubleX8#, fnmaddDoubleX8#, fnmsubDoubleX8#)

-- Genuine 512-bit Core with distinct lane permutations and vector call/return
-- boundaries. Native observations come from the separately compiled scalar FMA
-- lane oracle; this fixture does not claim native AVX512 instruction parity.

{-# NOINLINE hugeAddWorker #-}
{-# NOINLINE hugeSubWorker #-}
{-# NOINLINE hugeNegAddWorker #-}
{-# NOINLINE hugeNegSubWorker #-}
hugeAddWorker, hugeSubWorker, hugeNegAddWorker, hugeNegSubWorker :: FloatX16# -> FloatX16# -> FloatX16# -> FloatX16#
hugeAddWorker x y z = fmaddFloatX16# x y z
hugeSubWorker x y z = fmsubFloatX16# x y z
hugeNegAddWorker x y z = fnmaddFloatX16# x y z
hugeNegSubWorker x y z = fnmsubFloatX16# x y z

{-# NOINLINE hugeApply #-}
hugeApply :: (FloatX16# -> FloatX16#) -> FloatX16# -> FloatX16#
hugeApply operation value = operation value

{-# INLINE hugeLaneBits #-}
hugeLaneBits :: (FloatX16# -> FloatX16# -> FloatX16# -> FloatX16#) -> Word# -> Word# -> Word# -> Int# -> Word#
hugeLaneBits operation xb yb zb lane =
  case castWord32ToFloat# (wordToWord32# xb) of { x ->
  case castWord32ToFloat# (wordToWord32# yb) of { y ->
  case castWord32ToFloat# (wordToWord32# zb) of { z ->
  case hugeApply (operation (packFloatX16# (# x, y, z, x, negateFloat# x, x, negateFloat# y, z, negateFloat# x, negateFloat# y, negateFloat# z, negateFloat# x, x, negateFloat# x, y, negateFloat# z #))
                              (packFloatX16# (# y, z, x, y, y, negateFloat# y, z, negateFloat# x, negateFloat# y, negateFloat# z, negateFloat# x, negateFloat# y, negateFloat# y, y, negateFloat# z, x #)))
                     (packFloatX16# (# z, x, y, negateFloat# z, z, z, negateFloat# x, negateFloat# y, negateFloat# z, negateFloat# x, negateFloat# y, z, negateFloat# z, negateFloat# z, x, y #)) of { vector ->
  case unpackFloatX16# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7, v8, v9, v10, v11, v12, v13, v14, v15 #) ->
  word32ToWord# (castFloatToWord32# (case lane of
    0# -> v0; 1# -> v1; 2# -> v2; 3# -> v3; 4# -> v4; 5# -> v5; 6# -> v6; 7# -> v7; 8# -> v8; 9# -> v9; 10# -> v10; 11# -> v11; 12# -> v12; 13# -> v13; 14# -> v14; _ -> v15)) } } } } }

hugeAddCase, hugeSubCase, hugeNegAddCase, hugeNegSubCase :: Word# -> Word# -> Word# -> Int# -> Word#
hugeAddCase = hugeLaneBits hugeAddWorker
hugeSubCase = hugeLaneBits hugeSubWorker
hugeNegAddCase = hugeLaneBits hugeNegAddWorker
hugeNegSubCase = hugeLaneBits hugeNegSubWorker

{-# NOINLINE doubleHugeAddWorker #-}
{-# NOINLINE doubleHugeSubWorker #-}
{-# NOINLINE doubleHugeNegAddWorker #-}
{-# NOINLINE doubleHugeNegSubWorker #-}
doubleHugeAddWorker, doubleHugeSubWorker, doubleHugeNegAddWorker, doubleHugeNegSubWorker :: DoubleX8# -> DoubleX8# -> DoubleX8# -> DoubleX8#
doubleHugeAddWorker x y z = fmaddDoubleX8# x y z
doubleHugeSubWorker x y z = fmsubDoubleX8# x y z
doubleHugeNegAddWorker x y z = fnmaddDoubleX8# x y z
doubleHugeNegSubWorker x y z = fnmsubDoubleX8# x y z

{-# NOINLINE doubleHugeApply #-}
doubleHugeApply :: (DoubleX8# -> DoubleX8#) -> DoubleX8# -> DoubleX8#
doubleHugeApply operation value = operation value

{-# INLINE doubleHugeLaneBits #-}
doubleHugeLaneBits :: (DoubleX8# -> DoubleX8# -> DoubleX8# -> DoubleX8#) -> Word# -> Word# -> Word# -> Int# -> Word#
doubleHugeLaneBits operation xb yb zb lane =
  case castWord64ToDouble# (wordToWord64# xb) of { x ->
  case castWord64ToDouble# (wordToWord64# yb) of { y ->
  case castWord64ToDouble# (wordToWord64# zb) of { z ->
  case doubleHugeApply (operation (packDoubleX8# (# x, y, z, negateDouble# x, negateDouble# x, negateDouble# y, negateDouble# z, x #))
                              (packDoubleX8# (# y, z, x, y, negateDouble# y, negateDouble# z, negateDouble# x, negateDouble# y #)))
                     (packDoubleX8# (# z, negateDouble# x, y, negateDouble# z, negateDouble# z, x, negateDouble# y, z #)) of { vector ->
  case unpackDoubleX8# vector of { (# v0, v1, v2, v3, v4, v5, v6, v7 #) ->
  word64ToWord# (castDoubleToWord64# (case lane of
    0# -> v0; 1# -> v1; 2# -> v2; 3# -> v3; 4# -> v4; 5# -> v5; 6# -> v6; _ -> v7)) } } } } }

doubleHugeAddCase, doubleHugeSubCase, doubleHugeNegAddCase, doubleHugeNegSubCase :: Word# -> Word# -> Word# -> Int# -> Word#
doubleHugeAddCase = doubleHugeLaneBits doubleHugeAddWorker
doubleHugeSubCase = doubleHugeLaneBits doubleHugeSubWorker
doubleHugeNegAddCase = doubleHugeLaneBits doubleHugeNegAddWorker
doubleHugeNegSubCase = doubleHugeLaneBits doubleHugeNegSubWorker
