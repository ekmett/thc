-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module Lifecycle (lifecycleProbe#) where
import GHC.Exts (Int#)
foreign import ccall unsafe "archive_lifecycle" lifecycleProbe# :: Int# -> Int#
