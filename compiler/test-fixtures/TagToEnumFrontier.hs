-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, TypeFamilies #-}

-- |
-- Module      : TagToEnumFrontier
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Valid GHC enums outside THC's deliberately concrete, non-family slice.
module TagToEnumFrontier where
import GHC.Exts

data Parameterized a = P0 | P1
{-# OPAQUE parameterized #-}
parameterized :: Int# -> Parameterized Int
parameterized n = tagToEnum# n

data family Family a
data instance Family Int = F0 | F1
{-# OPAQUE family #-}
family :: Int# -> Family Int
family n = tagToEnum# n
