-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : ByteStringSortAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for byte string sort audit Core and metadata.
module ByteStringSortAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import Data.Word (Word8)

type SortCall = Addr# -> Word64# -> State# RealWorld -> (# State# RealWorld #)

-- Supplied only with the genuine installed FCallId of exactly this GHC type.
sortBytes :: SortCall -> Addr# -> Int# -> Int#
sortBytes call address count =
  case call address (int64ToWord64# (intToInt64# count)) realWorld# of
    (# _ #) -> count

nativeSortBytes :: SortCall -> Ptr Word8 -> Int -> IO ()
nativeSortBytes call (Ptr address) (I# count) = IO (\s ->
  case call address (int64ToWord64# (intToInt64# count)) s of
    (# next #) -> (# next, () #))
