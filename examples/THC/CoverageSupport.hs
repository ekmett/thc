-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : THC.CoverageSupport
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Example entry points exercising coverage support.
module THC.CoverageSupport (neverInt, expensiveInt, combine3) where

-- These boundaries keep bottom, sharing, and partial applications observable
-- after -O2; the surrounding algorithms remain ordinary optimizable Haskell.
{-# OPAQUE neverInt #-}
neverInt :: Int
neverInt = neverInt

{-# OPAQUE expensiveInt #-}
expensiveInt :: Int -> Int
expensiveInt n = n * n + 19

{-# OPAQUE combine3 #-}
combine3 :: Int -> Int -> Int -> Int
combine3 first _ lastValue = first * 31 + lastValue
