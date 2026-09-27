-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : Dep
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell 2010; base only
--
-- Compiler fixture for dep Core and metadata.
module Dep (Pair(..), mkPair, pairScore) where

data Pair = Pair !Int !Int

{-# NOINLINE mkPair #-}
mkPair :: Int -> Pair
mkPair x = Pair (x + 7) (x * 3)

{-# NOINLINE pairScore #-}
pairScore :: Pair -> Int
pairScore (Pair a b) = a * 2 + b
