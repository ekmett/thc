-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module Provider (nativeMath, process) where
import GHC.Exts (Double#, Int#)
foreign import ccall unsafe "archive_provider_math" nativeMath :: Double# -> Double#
foreign import ccall unsafe "archive_provider_process" process :: Int# -> Int#
