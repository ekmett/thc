-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : Haskell2010
--
-- A newtype implementation that multiplies by three.
module Multiplicative (Number, fromInt, toInt, step) where

newtype Number = Number Int
fromInt :: Int -> Number
fromInt = Number
toInt :: Number -> Int
toInt (Number value) = value
step :: Number -> Number
step (Number value) = Number (value * 3)
