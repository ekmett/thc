-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module Narrow (narrow, allowed) where
import GHC.Exts (Int#, Int32#)
foreign import ccall unsafe "archive_width" narrow :: Int# -> Int32#
foreign import ccall unsafe "archive_allowed" allowed :: Int# -> Int#
