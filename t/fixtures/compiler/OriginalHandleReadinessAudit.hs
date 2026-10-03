-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalHandleReadinessAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : POSIX; depends on the unix package
--
-- Compiler fixture for original handle readiness audit Core and metadata.
module OriginalHandleReadinessAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Foreign.C.Types (CInt(..))
import GHC.Internal.System.Posix.Internals (c_isatty)

-- Import the original GHC declaration so the exported Core carries its
-- real foreign-call descriptor and result representation.
{-# OPAQUE originalIsTerminal #-}
originalIsTerminal :: Int# -> Int#
originalIsTerminal descriptor = runRW# (\state ->
  case c_isatty (CInt (I32# (intToInt32# descriptor))) of { IO action ->
  case action state of { (# _, CInt (I32# result) #) -> int32ToInt# result } })
