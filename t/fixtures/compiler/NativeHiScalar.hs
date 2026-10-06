-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE UnboxedTuples #-}
-- Retain exact rational literals, including 1/3, to check direct IEEE rounding.
{-# OPTIONS_GHC -fexcess-precision #-}
-- Native .hi execution checks private retained RHSs, cross-module calls and
-- recursive scalar cases and IEEE literal rounding against NativeHiOracle.
module NativeHiScalar (entry, recursive, floatingPair) where

import GHC.Exts
  ( Int#, Float#, Double#, (+#), (-#), (*#), (<=#)
  , divideFloat#, (/##)
  )
import NativeHiDependency (marker)

{-# OPAQUE privateWorker #-}
privateWorker :: Int# -> Int#
privateWorker x = x *# 3# +# 1#

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = privateWorker x +# marker x

{-# OPAQUE recursive #-}
recursive :: Int# -> Int#
recursive x = case x <=# 0# of
  1# -> 0#
  _ -> x +# recursive (x -# 1#)

-- Keep the floating result opaque so GHC cannot fold its consumer's bit cast.
{-# OPAQUE floatValue #-}
floatValue :: Int# -> Float#
floatValue x = case x of
  -3# -> divideFloat# 1.0# 3.0#
  0# -> 1.000000059604644775390625#
  2# -> 1.000000178813934326171875#
  7# -> -1.0e-45#
  _ -> 3.4028236692093846e38#

{-# OPAQUE doubleValue #-}
doubleValue :: Int# -> Double#
doubleValue x = case x of
  -3# -> 1.0## /## 3.0##
  0# -> 1.00000000000000011102230246251565404236316680908203125##
  2# -> 1.00000000000000033306690738754696212708950042724609375##
  7# -> -4.0e-324##
  _ -> 1.7976931348623159e308##

-- Keep an actual cross-module unboxed result with distinct Float#/Double# lanes.
{-# OPAQUE floatingPair #-}
floatingPair :: Int# -> (# Float#, Double# #)
floatingPair x = (# floatValue x, doubleValue x #)
