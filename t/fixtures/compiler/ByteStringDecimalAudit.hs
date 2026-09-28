-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : ByteStringDecimalAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for byte string decimal audit Core and metadata.
module ByteStringDecimalAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Int (Int64(..))
import GHC.Ptr (Ptr(..))
import Data.Word (Word8)

type DecimalCall = Int64# -> Addr# -> State# RealWorld -> (# State# RealWorld, Addr# #)
type PaddedCall = Int64# -> Addr# -> State# RealWorld -> (# State# RealWorld #)

-- Supplied only with genuine installed FCallIds of exactly these GHC types.
decimal :: DecimalCall -> Int# -> Addr# -> Addr#
decimal call value address = case call (intToInt64# value) address realWorld# of
  (# _, end #) -> end

padded18 :: PaddedCall -> Int# -> Addr# -> Int#
padded18 call value address = case call (intToInt64# value) address realWorld# of
  (# _ #) -> 18#

nativeDecimal :: DecimalCall -> Int64 -> Ptr Word8 -> IO (Ptr Word8)
nativeDecimal call (I64# value) (Ptr address) = IO (\s -> case call value address s of
  (# next, end #) -> (# next, Ptr end #))

nativePadded18 :: PaddedCall -> Int64 -> Ptr Word8 -> IO ()
nativePadded18 call (I64# value) (Ptr address) = IO (\s -> case call value address s of
  (# next #) -> (# next, () #))
