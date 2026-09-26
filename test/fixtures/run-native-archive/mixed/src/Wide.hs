-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module Wide (wide) where
import GHC.Exts (Int#, Int64#)
foreign import ccall unsafe "archive_width" wide :: Int# -> Int64#
