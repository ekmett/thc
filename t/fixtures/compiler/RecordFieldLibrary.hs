-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DuplicateRecordFields, MagicHash #-}

-- |
-- Module      : RecordFieldLibrary
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for record field library Core and metadata.
module RecordFieldLibrary (LeftRecord(..), RightRecord(..), Plain(..),
                           applyPlain, leftValue, rightValue) where

import GHC.Exts

data LeftRecord = LeftRecord { shared :: Int }
data RightRecord = RightRecord { shared :: Int }
data Plain = Plain { unique :: Int }

{-# OPAQUE applyPlain #-}
applyPlain :: (Plain -> Int) -> Int# -> Int#
applyPlain getter n = case getter (Plain (I# n)) of I# value -> value

{-# OPAQUE leftValue #-}
leftValue :: LeftRecord -> Int
leftValue LeftRecord{shared=value} = value

{-# OPAQUE rightValue #-}
rightValue :: RightRecord -> Int
rightValue RightRecord{shared=value} = value
