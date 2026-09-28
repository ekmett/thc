-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : FloatForeignAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for float foreign audit Core and metadata.
module FloatForeignAudit where

import GHC.Exts

type FloatPredicate = Float# -> State# RealWorld -> (# State# RealWorld, Int# #)
type DoublePredicate = Double# -> State# RealWorld -> (# State# RealWorld, Int# #)
type FloatRound = Float# -> State# RealWorld -> (# State# RealWorld, Float# #)
type DoubleRound = Double# -> State# RealWorld -> (# State# RealWorld, Double# #)

-- These higher-order consumers receive genuine installed FCallIds in the
-- producer. Raw-bit inputs/results retain NaNs, signed zeros and subnormals.
testFloatPredicate :: FloatPredicate -> Int# -> Int#
testFloatPredicate call bits =
  case call (castWord32ToFloat# (wordToWord32# (int2Word# bits))) realWorld# of
    (# _, answer #) -> answer

testDoublePredicate :: DoublePredicate -> Int# -> Int#
testDoublePredicate call bits =
  case call (castWord64ToDouble# (wordToWord64# (int2Word# bits))) realWorld# of
    (# _, answer #) -> answer

testFloatRound :: FloatRound -> Int# -> Int#
testFloatRound call bits =
  case call (castWord32ToFloat# (wordToWord32# (int2Word# bits))) realWorld# of
    (# _, answer #) -> word2Int# (word32ToWord# (castFloatToWord32# answer))

testDoubleRound :: DoubleRound -> Int# -> Int#
testDoubleRound call bits =
  case call (castWord64ToDouble# (wordToWord64# (int2Word# bits))) realWorld# of
    (# _, answer #) -> word2Int# (word64ToWord# (castDoubleToWord64# answer))

floatNaN :: FloatPredicate -> Int# -> Int#
floatNaN = testFloatPredicate
nativeFloatNaN :: FloatPredicate -> Int -> Int
nativeFloatNaN call (I# bits) = I# (floatNaN call bits)

floatInfinite :: FloatPredicate -> Int# -> Int#
floatInfinite = testFloatPredicate
nativeFloatInfinite :: FloatPredicate -> Int -> Int
nativeFloatInfinite call (I# bits) = I# (floatInfinite call bits)

floatFinite :: FloatPredicate -> Int# -> Int#
floatFinite = testFloatPredicate
nativeFloatFinite :: FloatPredicate -> Int -> Int
nativeFloatFinite call (I# bits) = I# (floatFinite call bits)

floatDenormalized :: FloatPredicate -> Int# -> Int#
floatDenormalized = testFloatPredicate
nativeFloatDenormalized :: FloatPredicate -> Int -> Int
nativeFloatDenormalized call (I# bits) = I# (floatDenormalized call bits)

floatNegativeZero :: FloatPredicate -> Int# -> Int#
floatNegativeZero = testFloatPredicate
nativeFloatNegativeZero :: FloatPredicate -> Int -> Int
nativeFloatNegativeZero call (I# bits) = I# (floatNegativeZero call bits)

floatRound :: FloatRound -> Int# -> Int#
floatRound = testFloatRound
nativeFloatRound :: FloatRound -> Int -> Int
nativeFloatRound call (I# bits) = I# (floatRound call bits)

doubleNaN :: DoublePredicate -> Int# -> Int#
doubleNaN = testDoublePredicate
nativeDoubleNaN :: DoublePredicate -> Int -> Int
nativeDoubleNaN call (I# bits) = I# (doubleNaN call bits)

doubleInfinite :: DoublePredicate -> Int# -> Int#
doubleInfinite = testDoublePredicate
nativeDoubleInfinite :: DoublePredicate -> Int -> Int
nativeDoubleInfinite call (I# bits) = I# (doubleInfinite call bits)

doubleFinite :: DoublePredicate -> Int# -> Int#
doubleFinite = testDoublePredicate
nativeDoubleFinite :: DoublePredicate -> Int -> Int
nativeDoubleFinite call (I# bits) = I# (doubleFinite call bits)

doubleDenormalized :: DoublePredicate -> Int# -> Int#
doubleDenormalized = testDoublePredicate
nativeDoubleDenormalized :: DoublePredicate -> Int -> Int
nativeDoubleDenormalized call (I# bits) = I# (doubleDenormalized call bits)

doubleNegativeZero :: DoublePredicate -> Int# -> Int#
doubleNegativeZero = testDoublePredicate
nativeDoubleNegativeZero :: DoublePredicate -> Int -> Int
nativeDoubleNegativeZero call (I# bits) = I# (doubleNegativeZero call bits)

doubleRound :: DoubleRound -> Int# -> Int#
doubleRound = testDoubleRound
nativeDoubleRound :: DoubleRound -> Int -> Int
nativeDoubleRound call (I# bits) = I# (doubleRound call bits)

