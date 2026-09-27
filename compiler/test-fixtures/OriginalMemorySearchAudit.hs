-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalMemorySearchAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original memory search audit Core and metadata.
module OriginalMemorySearchAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Int (Int32(..))
import GHC.Ptr (Ptr(..))
import GHC.Word (Word8(..), Word64(..))
import Foreign.C.Types (CInt(..), CSize(..))
import qualified Data.ByteString.Internal as B

-- Call the installed library wrappers. GHC supplies their real foreign Ids;
-- no new foreign declaration or reconstructed package body is involved.
originalCompare :: Addr# -> Addr# -> Int# -> Int#
originalCompare left right count = runRW# (\state ->
  case B.memcmp (Ptr left) (Ptr right) (I# count) of
    IO action -> case action state of
      (# _, CInt (I32# result) #) -> int32ToInt# result)

originalFind :: Addr# -> Int# -> Int# -> Addr#
originalFind source needle count = runRW# (\state ->
  case B.memchr (Ptr source) (W8# (wordToWord8# (int2Word# needle)))
      (CSize (W64# (wordToWord64# (int2Word# count)))) of
    IO action -> case action state of (# _, Ptr result #) -> result)
