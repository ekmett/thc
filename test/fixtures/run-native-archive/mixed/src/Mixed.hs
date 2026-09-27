-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, InterruptibleFFI, MagicHash, UnliftedFFITypes #-}

-- |
-- Module      : Mixed
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; declared foreign symbols required at link/run time
--
-- Module for the @run-native-archive@ integration fixture.
module Mixed (allowed, blocked, count) where
import GHC.Exts (Int#, Addr#)
foreign import ccall unsafe "archive_allowed" allowed :: Int# -> Int#
foreign import ccall interruptible "archive_blocked" blocked :: Addr# -> Int#
foreign import ccall unsafe "archive_count" count :: Int# -> Int#
