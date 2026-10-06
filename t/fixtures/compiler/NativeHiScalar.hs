-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE UnboxedSums #-}
{-# LANGUAGE UnboxedTuples #-}
-- Retain exact rational literals, including 1/3, to check direct IEEE rounding.
{-# OPTIONS_GHC -fexcess-precision #-}
-- Native .hi execution checks private retained RHSs, cross-module calls and
-- recursive scalar cases and IEEE literal rounding against NativeHiOracle.
module NativeHiScalar (entry, recursive, floatingPair, goodMain, badMain) where

import GHC.Exts
  ( Int(I#), Int#, Float#, Double#, (+#), (-#), (*#), (<=#), (<#)
  , divideFloat#, (/##), realWorld#, raise#
  )
import NativeHiDependency (marker, exchange)
import GHC.IO (IO(..))

{-# OPAQUE privateWorker #-}
privateWorker :: Int# -> Int#
privateWorker x = x *# 3# +# 1#

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case marker x realWorld# of
  (# _, value #) -> privateWorker x +# value

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

-- Cross-module transport mixes nested aggregates, boxed/unboxed alternatives
-- and distinct Float#/Double# lanes. Every payload contributes to oracle results.
{-# OPAQUE floatingPair #-}
floatingPair :: Int# -> (# (# Float#, Double# #), (# Int | Int# #) #)
floatingPair x = case x <# 0# of
  1# -> (# (# floatValue x, doubleValue x #), (# I# x | #) #)
  _ -> (# (# floatValue x, doubleValue x #), (# | x #) #)

data Observation = Observation Int#
data Failure = WrongEffect

-- Input: no host arguments. Output: boxed unit only after the cross-module
-- polymorphic IO action has written and read the replacement value. The failing
-- sibling is a negative control for the assertion, not a supported-language limit.
{-# OPAQUE observe #-}
observe :: Int# -> IO ()
observe expected = case exchange (Observation 0#) (Observation 41#) of
  IO step -> IO (\state -> case step state of
    (# next, Observation actual #) -> case actual -# expected of
      0# -> (# next, () #)
      _ -> raise# WrongEffect)

goodMain :: IO ()
goodMain = observe 41#

badMain :: IO ()
badMain = observe 42#
