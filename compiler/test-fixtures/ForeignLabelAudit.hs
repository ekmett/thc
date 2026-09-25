-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module ForeignLabelAudit (poolRelease, backtraceFree, enabledCapabilities) where

import Foreign.Ptr (FunPtr, Ptr)
import Data.Word (Word32)

foreign import ccall unsafe "&libdwPoolRelease" poolRelease :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "&backtraceFree" backtraceFree :: FunPtr (Ptr () -> IO ())
foreign import ccall unsafe "&enabled_capabilities" enabledCapabilities :: Ptr Word32
