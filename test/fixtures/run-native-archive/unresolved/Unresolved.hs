-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module Unresolved (process) where
import GHC.Exts (Int#)
foreign import ccall unsafe "archive_process" process :: Int# -> Int#
