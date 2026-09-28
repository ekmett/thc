-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | Mixed primitive carriers for the experimental selected native code cache.
module THC.CachedNumeric (calculate) where

import GHC.Exts
  ( Int16#, Float(F#), Float#, Double#, intToInt16#, int16ToInt#
  , plusInt16#, plusFloat#, float2Double#, int2Double#, (+##), (*##) )

data Numeric = Numeric Int16# Float# Double#

{-# OPAQUE pack #-}
pack :: Int16# -> Float# -> Double# -> Numeric
pack narrow single wide = Numeric narrow single wide

{-# OPAQUE offset #-}
offset :: Float
offset = F# 0.5#

-- | Add one with Int16# wrapping, round the Float# addition before widening,
-- and combine it with a dynamic Double# term. Each load owns its offset CAF.
{-# OPAQUE calculate #-}
calculate :: Int16# -> Float# -> Double# -> Double#
calculate n f d = case offset of
  F# bias -> case pack n f d of
    Numeric narrow single wide ->
      int2Double# (int16ToInt# (plusInt16# narrow (intToInt16# 1#)))
        +## float2Double# (plusFloat# single bias) +## (wide *## 2.0##)
