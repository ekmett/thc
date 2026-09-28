-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}

-- |
-- Module      : OriginalProcessIdentityAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : POSIX; depends on the unix package
--
-- Compiler fixture for original process identity audit Core and metadata.
module OriginalProcessIdentityAudit where

import GHC.Exts
import GHC.IO (IO(..))
import GHC.Internal.Int (Int32(I32#))
import GHC.Internal.Word (Word32(W32#))
import GHC.Internal.System.Posix.Types (CPid(..), CUid(..))
import GHC.Internal.System.Posix.Internals (c_getpid)
import System.Posix.User (getEffectiveUserID)

-- The installed functions supply their original FCallIds. Dynamic inputs keep
-- these small runRW# test entries distinct from shared CAFs.
originalGetPid :: Int# -> Int#
originalGetPid input = runRW# (\state -> case c_getpid of { IO query ->
  case query state of { (# _, CPid (I32# value) #) -> input +# int32ToInt# value } })

originalGetEuid :: Word# -> Word#
originalGetEuid input = runRW# (\state -> case getEffectiveUserID of { IO query ->
  case query state of { (# _, CUid (W32# value) #) -> plusWord# input (word32ToWord# value) } })
