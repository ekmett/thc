-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}

-- |
-- Module      : Unknown
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; declared foreign symbols required at link/run time
--
-- Module for the @run-native-archive@ integration fixture.
module Unknown (pointer, other, callPointer) where
import Foreign.Ptr (Ptr, FunPtr)
import GHC.Exts (Int#)
foreign import ccall unsafe "&archive_other" pointer :: Ptr ()
foreign import ccall unsafe "archive_other" other :: Int# -> Int#
-- Static labels now have their own exact stock-GHC provenance. Keep this
-- negative genuinely non-static instead of treating a supported sibling label
-- as evidence that all ordinary calls in this module must be rejected.
foreign import ccall unsafe "dynamic" callPointer :: FunPtr (Int -> IO Int) -> Int -> IO Int
