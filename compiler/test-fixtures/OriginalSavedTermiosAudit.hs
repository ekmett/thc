-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
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
