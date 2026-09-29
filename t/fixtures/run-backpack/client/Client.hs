-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
-- |
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC Backpack
--
-- The same client is compiled against two different implementations of Number.
module Client (evaluate) where

import Number

-- | Cross the abstract type boundary without exposing its representation.
{-# NOINLINE evaluate #-}
evaluate :: Int -> Int
evaluate value = toInt (step (fromInt value))
