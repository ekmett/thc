-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
{-# OPTIONS_GHC -Wno-deprecations #-}

-- |
-- Module      : OriginalMemsetAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for original memset audit Core and metadata.
module OriginalMemsetAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Ptr (Ptr(..))
import GHC.Word (Word8(..), Word64(..))
import Foreign.C.Types (CSize(..))
import qualified Data.ByteString.Internal as B

-- The installed wrapper supplies its real CInt/CSize FCallId. Its Word8
-- argument has the same low-byte effect as any C int with those low bits.
originalFill :: Addr# -> Int# -> Int# -> Addr#
originalFill destination value count = runRW# (\state ->
  case B.memset (Ptr destination) (W8# (wordToWord8# (int2Word# value)))
      (CSize (W64# (wordToWord64# (int2Word# count)))) of
    IO action -> case action state of (# _, Ptr result #) -> result)
