-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module Unknown (pointer, other) where
import Foreign.Ptr (Ptr)
import GHC.Exts (Int#)
foreign import ccall unsafe "&archive_other" pointer :: Ptr ()
foreign import ccall unsafe "archive_other" other :: Int# -> Int#
