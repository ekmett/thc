-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module FloatDecodeAudit where
import GHC.Exts

{-# OPAQUE floatDirect #-}
floatDirect :: Int# -> Int# -> Int#
floatDirect bits field = case decodeFloat_Int# (castWord32ToFloat# (wordToWord32# (int2Word# bits))) of
  (# m, e #) -> case field of 0# -> m; _ -> e

{-# OPAQUE doubleDirect #-}
doubleDirect :: Int# -> Int# -> Int#
doubleDirect bits field = case decodeDouble_Int64# (castWord64ToDouble# (wordToWord64# (int2Word# bits))) of
  (# m, e #) -> case field of 0# -> int64ToInt# m; _ -> e

-- Retain genuine opaque floating arguments and two-field return boundaries.
-- The reversible adjustment prevents eta reduction to a bare primitive.
{-# OPAQUE floatWorker #-}
floatWorker :: Float# -> (# Int#, Int# #)
floatWorker x = case decodeFloat_Int# x of (# m, e #) -> (# m, e +# 1# #)
{-# OPAQUE doubleWorker #-}
doubleWorker :: Double# -> (# Int64#, Int# #)
doubleWorker x = case decodeDouble_Int64# x of (# m, e #) -> (# m, e +# 1# #)

{-# OPAQUE floatCall #-}
floatCall :: Int# -> Int# -> Int#
floatCall bits field = case floatWorker (castWord32ToFloat# (wordToWord32# (int2Word# bits))) of
  (# m, e #) -> case field of 0# -> m; _ -> e -# 1#
{-# OPAQUE doubleCall #-}
doubleCall :: Int# -> Int# -> Int#
doubleCall bits field = case doubleWorker (castWord64ToDouble# (wordToWord64# (int2Word# bits))) of
  (# m, e #) -> case field of 0# -> int64ToInt# m; _ -> e -# 1#

-- Ordinary RealFloat instance methods, not a replacement definition of exponent.
{-# OPAQUE floatExponent #-}
floatExponent :: Int# -> Int# -> Int#
floatExponent bits _ = case exponent (F# (castWord32ToFloat# (wordToWord32# (int2Word# bits)))) of I# e -> e
{-# OPAQUE doubleExponent #-}
doubleExponent :: Int# -> Int# -> Int#
doubleExponent bits _ = case exponent (D# (castWord64ToDouble# (wordToWord64# (int2Word# bits)))) of I# e -> e
