-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.FloatDecode (floatExampleExponent, doubleExampleExponent) where

import GHC.Exts

-- Integer bit-pattern inputs fit THC's scalar command-line entry contract.
-- The calculation itself is the ordinary RealFloat instance method.
{-# OPAQUE floatExampleExponent #-}
floatExampleExponent :: Int# -> Int#
floatExampleExponent bits =
  case exponent (F# (castWord32ToFloat# (wordToWord32# (int2Word# bits)))) of I# e -> e

{-# OPAQUE doubleExampleExponent #-}
doubleExampleExponent :: Int# -> Int#
doubleExampleExponent bits =
  case exponent (D# (castWord64ToDouble# (wordToWord64# (int2Word# bits)))) of I# e -> e
