-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module SqrtAudit where
import GHC.Exts

-- The public Prelude API must produce the exact primitive operations in Core.
{-# OPAQUE sqrtFloat #-}
sqrtFloat :: Float# -> Float#
sqrtFloat x = case sqrt (F# x) of F# result -> result
{-# OPAQUE sqrtDouble #-}
sqrtDouble :: Double# -> Double#
sqrtDouble x = case sqrt (D# x) of D# result -> result

-- Dynamic scalar consumers also exercise producer calls and primitive locals.
floatCase, doubleCase :: Int# -> Int#
floatCase n = float2Int# (sqrtFloat (int2Float# n))
doubleCase n = double2Int# (sqrtDouble (int2Double# n))
