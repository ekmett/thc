-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : ByteStringUtf8Audit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for byte string utf8 audit Core and metadata.
module ByteStringUtf8Audit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Int (Int32(..))
import GHC.Ptr (Ptr(..))
import GHC.Word (Word64(..))
import Foreign.C.Types (CInt(..), CSize(..))
import qualified Data.ByteString.Internal.Type as B

-- Use the installed declarations so the exporter retains their original
-- FCallIds, package units, pointer ABI, and distinct safety annotations.
validateUnsafe :: Addr# -> Int# -> Int#
validateUnsafe source count = runRW# (\state ->
  case B.cIsValidUtf8 (Ptr source) (CSize (W64# (wordToWord64# (int2Word# count)))) of
    IO action -> case action state of
      (# _, CInt (I32# result) #) -> int32ToInt# result)

validateSafe :: Addr# -> Int# -> Int#
validateSafe source count = runRW# (\state ->
  case B.cIsValidUtf8Safe (Ptr source) (CSize (W64# (wordToWord64# (int2Word# count)))) of
    IO action -> case action state of
      (# _, CInt (I32# result) #) -> int32ToInt# result)
