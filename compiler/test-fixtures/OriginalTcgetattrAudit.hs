-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalTcgetattrAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 internal library APIs
--
-- Compiler fixture for original tcgetattr audit Core and metadata.
module OriginalTcgetattrAudit where

import GHC.Exts
import GHC.IO (IO(..))
import qualified GHC.Internal.System.Posix.Internals as P

-- The installed declaration, not a substitute foreign import.
originalTcgetattr :: Int# -> Addr# -> Int#
originalTcgetattr fd address = runRW# (\state -> case P.c_tcgetattr (fromIntegral (I# fd)) (Ptr address) of { IO action ->
  case action state of { (# _, result #) -> case fromIntegral result :: Int of I# value -> value } })
