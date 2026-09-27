-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : GcStatsAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for gc stats audit Core and metadata.
module GcStatsAudit where

import GHC.Exts
import GHC.IO (IO(..))

-- Typed consumers, not replacement RTS declarations. The producer specializes
-- them with the private FCallIds recovered from original installed interfaces.
type Enabled = State# RealWorld -> (# State# RealWorld, Int# #)
type Collect = State# RealWorld -> (# State# RealWorld #)
type Stats = Addr# -> State# RealWorld -> (# State# RealWorld #)
type Clock = State# RealWorld -> (# State# RealWorld, Word64# #)

originalEnabled :: Enabled -> Int# -> Int#
originalEnabled call x = case call realWorld# of (# _, n #) -> x +# n

originalCollect :: Collect -> Int# -> Int#
originalCollect call x = case call realWorld# of (# _ #) -> x +# 1#

originalStats :: Stats -> Addr# -> Int# -> Int#
originalStats call p x = case call p realWorld# of (# _ #) -> x +# 1#

originalClock :: Clock -> Int# -> Int#
originalClock call x = case call realWorld# of
  (# s, a #) -> case call s of (# _, b #) -> x +# leWord64# a b

nativeEnabled :: Enabled -> Int -> IO Int
nativeEnabled call (I# x) = IO (\s -> case call s of (# next, n #) -> (# next, I# (x +# n) #))

nativeCollect :: Collect -> Int -> IO Int
nativeCollect call (I# x) = IO (\s -> case call s of (# next #) -> (# next, I# (x +# 1#) #))

nativeClock :: Clock -> Int -> IO Int
nativeClock call (I# x) = IO (\s -> case call s of
  (# s1, a #) -> case call s1 of (# next, b #) -> (# next, I# (x +# leWord64# a b) #))
