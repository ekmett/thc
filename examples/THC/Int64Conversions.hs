-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, ExtendedLiterals, NegativeLiterals #-}
module THC.Int64Conversions (toInt64, fromInt64, int64Roundtrip, int64LiteralBoundary) where

import GHC.Exts

-- Keep both typed scalar entry boundaries in exported Core and native code.
{-# NOINLINE toInt64 #-}
toInt64 :: Int# -> Int64#
toInt64 x = intToInt64# x

{-# NOINLINE fromInt64 #-}
fromInt64 :: Int64# -> Int#
fromInt64 x = int64ToInt# x

int64Roundtrip :: Int# -> Int#
int64Roundtrip x = fromInt64 (toInt64 x)

int64LiteralBoundary :: Int# -> Int#
int64LiteralBoundary x = case x of
  0# -> fromInt64 9223372036854775807#Int64
  _ -> fromInt64 (-9223372036854775808#Int64)
