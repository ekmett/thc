-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : DeepEvaluation
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for deep evaluation Core and metadata.
module DeepEvaluation (probe, boxedProbe) where
import GHC.Exts (Int(I#), Int#)

-- Model forward differentiation's strict primal and lazy accumulated tangent.
data Forward = Forward !Int Int

{-# NOINLINE plus #-}
plus :: Forward -> Forward -> Forward
plus (Forward x dx) (Forward y dy) = Forward (x + y) (dx + dy)

{-# NOINLINE accumulate #-}
accumulate :: Int -> Forward
accumulate n = foldl plus (Forward 0 0) (replicate n (Forward 0 1))

boxedProbe :: Int -> Int
boxedProbe n = case accumulate n of Forward _ tangent -> tangent

probe :: Int# -> Int#
probe n = case boxedProbe (I# n) of I# answer -> answer
