-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

module Dep (Pair(..), mkPair, pairScore) where

data Pair = Pair !Int !Int

{-# NOINLINE mkPair #-}
mkPair :: Int -> Pair
mkPair x = Pair (x + 7) (x * 3)

{-# NOINLINE pairScore #-}
pairScore :: Pair -> Int
pairScore (Pair a b) = a * 2 + b
