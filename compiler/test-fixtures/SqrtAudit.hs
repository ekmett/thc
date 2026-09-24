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

-- Keep each GHC scalar math operation visible at a typed Core boundary.
{-# OPAQUE fabsFloat #-}
fabsFloat :: Float# -> Float#
fabsFloat x = fabsFloat# x
{-# OPAQUE fabsDouble #-}
fabsDouble :: Double# -> Double#
fabsDouble x = fabsDouble# x

{-# OPAQUE expFloat #-}
expFloat :: Float# -> Float#
expFloat x = expFloat# x
{-# OPAQUE expDouble #-}
expDouble :: Double# -> Double#
expDouble x = expDouble# x

{-# OPAQUE expm1Float #-}
expm1Float :: Float# -> Float#
expm1Float x = expm1Float# x
{-# OPAQUE expm1Double #-}
expm1Double :: Double# -> Double#
expm1Double x = expm1Double# x

{-# OPAQUE logFloat #-}
logFloat :: Float# -> Float#
logFloat x = logFloat# x
{-# OPAQUE logDouble #-}
logDouble :: Double# -> Double#
logDouble x = logDouble# x

{-# OPAQUE log1pFloat #-}
log1pFloat :: Float# -> Float#
log1pFloat x = log1pFloat# x
{-# OPAQUE log1pDouble #-}
log1pDouble :: Double# -> Double#
log1pDouble x = log1pDouble# x

{-# OPAQUE sinFloat #-}
sinFloat :: Float# -> Float#
sinFloat x = sinFloat# x
{-# OPAQUE sinDouble #-}
sinDouble :: Double# -> Double#
sinDouble x = sinDouble# x

{-# OPAQUE cosFloat #-}
cosFloat :: Float# -> Float#
cosFloat x = cosFloat# x
{-# OPAQUE cosDouble #-}
cosDouble :: Double# -> Double#
cosDouble x = cosDouble# x

{-# OPAQUE tanFloat #-}
tanFloat :: Float# -> Float#
tanFloat x = tanFloat# x

{-# OPAQUE tanDouble #-}
tanDouble :: Double# -> Double#
tanDouble x = tanDouble# x

{-# OPAQUE asinFloat #-}
asinFloat :: Float# -> Float#
asinFloat x = asinFloat# x

{-# OPAQUE asinDouble #-}
asinDouble :: Double# -> Double#
asinDouble x = asinDouble# x

{-# OPAQUE acosFloat #-}
acosFloat :: Float# -> Float#
acosFloat x = acosFloat# x

{-# OPAQUE acosDouble #-}
acosDouble :: Double# -> Double#
acosDouble x = acosDouble# x

{-# OPAQUE atanFloat #-}
atanFloat :: Float# -> Float#
atanFloat x = atanFloat# x

{-# OPAQUE atanDouble #-}
atanDouble :: Double# -> Double#
atanDouble x = atanDouble# x

{-# OPAQUE sinhFloat #-}
sinhFloat :: Float# -> Float#
sinhFloat x = sinhFloat# x

{-# OPAQUE sinhDouble #-}
sinhDouble :: Double# -> Double#
sinhDouble x = sinhDouble# x

{-# OPAQUE coshFloat #-}
coshFloat :: Float# -> Float#
coshFloat x = coshFloat# x

{-# OPAQUE coshDouble #-}
coshDouble :: Double# -> Double#
coshDouble x = coshDouble# x

{-# OPAQUE tanhFloat #-}
tanhFloat :: Float# -> Float#
tanhFloat x = tanhFloat# x

{-# OPAQUE tanhDouble #-}
tanhDouble :: Double# -> Double#
tanhDouble x = tanhDouble# x

{-# OPAQUE powerFloat #-}
powerFloat :: Float# -> Float#
powerFloat x = powerFloat# x x
{-# OPAQUE powerDouble #-}
powerDouble :: Double# -> Double#
powerDouble x = x **## x

-- Dynamic scalar consumers also exercise producer calls and primitive locals.
floatCase, doubleCase :: Int# -> Int#
floatCase n = float2Int# (sqrtFloat (int2Float# n))
doubleCase n = double2Int# (sqrtDouble (int2Double# n))
