-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, ForeignFunctionInterface #-}
module NativeExport (probe) where

import Foreign.C (CInt(..))
import Foreign.StablePtr (StablePtr, deRefStablePtr, newStablePtr)

-- One module deliberately mixes a CAPI adapter, a header-qualified ccall,
-- and a static export. The callback is not a Haskell module export.
foreign import capi unsafe "callbacks.h native_adjust"
  adjust :: CInt -> IO CInt
foreign import ccall safe "callbacks.h native_roundtrip"
  roundtrip :: StablePtr (IO Int) -> CInt -> IO CInt
foreign export ccall "thc_pkg_callback"
  callback :: StablePtr (IO Int) -> CInt -> IO CInt

callback :: StablePtr (IO Int) -> CInt -> IO CInt
callback pointer value = do
  action <- deRefStablePtr pointer
  offset <- action
  adjusted <- adjust value
  pure (adjusted + fromIntegral offset)

probe :: IO Int
probe = do
  pointer <- newStablePtr (pure 7)
  fromIntegral <$> roundtrip pointer 35
