-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalSavedTermiosAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 internal library APIs
--
-- Compiler fixture for original saved termios audit Core and metadata.
module OriginalSavedTermiosAudit where

import GHC.Exts
import GHC.IO (IO(..))
import qualified GHC.Internal.System.Posix.Internals as P

-- Unchanged installed declarations; no replacement foreign imports.
originalGetSavedTermios :: Int# -> Addr#
originalGetSavedTermios fd = runRW# (\state -> case P.get_saved_termios (fromIntegral (I# fd)) of { IO action ->
  case action state of { (# _, Ptr result #) -> result } })

originalSetSavedTermios :: Int# -> Addr# -> Int#
originalSetSavedTermios fd address = runRW# (\state -> case P.set_saved_termios (fromIntegral (I# fd)) (Ptr address) of { IO action ->
  case action state of { (# _, () #) -> 0# } })
