-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
{-# LANGUAGE GADTs #-}
{-# LANGUAGE RankNTypes #-}
{-# LANGUAGE ScopedTypeVariables #-}
{-# LANGUAGE TypeApplications #-}
{-# LANGUAGE UnboxedSums #-}
{-# LANGUAGE UnboxedTuples #-}
-- Retain exact rational literals, including 1/3, to check direct IEEE rounding.
{-# OPTIONS_GHC -fexcess-precision #-}
-- Native .hi execution checks private retained RHSs, cross-module calls and
-- recursive scalar cases and IEEE literal rounding against NativeHiOracle.
module NativeHiScalar (entry, recursive, floatingPair, wired, goodMain, badMain) where

import GHC.Exts
  ( Int(I#), Int#, Float#, Double#, (+#), (-#), (*#), (<=#), (<#)
  , divideFloat#, (/##), State#, RealWorld, realWorld#, runRW#, raise#
  )
import GHC.Internal.Magic (lazy, noinline)
import GHC.Internal.Unsafe.Coerce (UnsafeEquality(..), unsafeEqualityProof)
import NativeHiDependency (marker, measured, advanceAndMeasure)
import NativeHiClasses (Measure(..), Advance(..))
import GHC.IO (IO(..))

{-# OPAQUE privateWorker #-}
privateWorker :: Int# -> Int#
privateWorker x = x *# 3# +# 1#

{-# OPAQUE entry #-}
entry :: Int# -> Int#
entry x = case marker x realWorld# of
  (# _, value #) -> measured (Observation (privateWorker x)) +# value

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
instance Measure Observation where
  measure (Observation value) = value

instance Advance Observation where
  advance (Observation value) = Observation (value +# 1#)

data Failure = WrongEffect

-- Input: no host arguments. Output: boxed unit only after the cross-module
-- polymorphic IO action has written and read the replacement value. The failing
-- sibling is a negative control for the assertion, not a supported-language limit.
{-# OPAQUE observe #-}
observe :: Int# -> IO ()
observe expected = case advanceAndMeasure (Observation 40#) of
  IO step -> IO (\state -> case step state of
    (# next, I# actual #) -> case actual -# expected of
      0# -> (# next, () #)
      _ -> raise# WrongEffect)

goodMain :: IO ()
goodMain = observe 42#

badMain :: IO ()
badMain = observe 43#

-- Mandatory preparation preserves x+3 for each form, including a payload that
-- must remain unevaluated. The opaque call boundaries keep first-class magic,
-- application suffixes and the polymorphic proof case in retained Core.
{-# OPAQUE wiredStep #-}
wiredStep :: Int# -> Int#
wiredStep x = x +# 3#

{-# OPAQUE wiredIdentity #-}
wiredIdentity :: (forall a. a -> a) -> Int# -> Int#
wiredIdentity identity x = identity wiredStep x

{-# OPAQUE wiredRunner #-}
wiredRunner :: ((State# RealWorld -> Int#) -> Int#) -> Int# -> Int#
wiredRunner runner x = runner (\_ -> wiredStep x)

{-# OPAQUE wiredDiscard #-}
wiredDiscard :: Int -> Int# -> Int#
wiredDiscard _ x = wiredStep x

{-# OPAQUE wiredBottom #-}
wiredBottom :: Int# -> Int
wiredBottom x = wiredBottom x

{-# OPAQUE wiredCoerce #-}
wiredCoerce :: forall a b. a -> b
wiredCoerce x = case unsafeEqualityProof @a @b of UnsafeRefl -> x

-- | @wired x = 7 * (x + 3)@, without forcing 'wiredBottom'.
{-# OPAQUE wired #-}
wired :: Int# -> Int#
wired x = lazy (noinline wiredStep) x
  +# wiredIdentity lazy x
  +# runRW# (\_ -> wiredStep x)
  +# wiredRunner runRW# x
  +# runRW# (\_ -> wiredStep) x
  +# (wiredCoerce wiredStep :: Int# -> Int#) x
  +# wiredDiscard (lazy (wiredBottom x)) x
