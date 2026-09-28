-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}

-- |
-- Module      : Provider
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; declared foreign symbols required at link/run time
--
-- Module for the @run-native-archive@ integration fixture.
module Provider (nativeMath, process) where
import GHC.Exts (Double#, Int#)
foreign import ccall unsafe "archive_provider_math" nativeMath :: Double# -> Double#
foreign import ccall unsafe "archive_provider_process" process :: Int# -> Int#
