-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE CApiFFI, ForeignFunctionInterface #-}
-- One independent native ABI observation for the shared Unix transport.
module Main (main) where

import Data.Word (Word64)
import Foreign.C.Types (CInt(..), CUInt(..), CLong(..))
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (Ptr)

foreign import capi unsafe "sys/sysmacros.h makedev"
  makeDevice :: CUInt -> CUInt -> IO Word64
foreign import ccall unsafe "sysconf"
  sysconf :: CInt -> IO CLong
foreign import capi unsafe "signal.h sigfillset"
  fillSet :: Ptr () -> IO CInt
foreign import capi unsafe "signal.h sigdelset"
  deleteSignal :: Ptr () -> CInt -> IO CInt
foreign import capi unsafe "signal.h sigismember"
  memberSignal :: Ptr () -> CInt -> IO CInt

main :: IO ()
main = do
  device <- makeDevice 0x80000000 0xffffffff
  page <- sysconf 30 -- Linux _SC_PAGESIZE, matching the selected LP64 runtime.
  image <- allocaBytes 128 $ \buffer -> do
    fillBytes buffer 0x5a 128
    filled <- fillSet buffer
    deleted <- deleteSignal buffer 2
    member <- memberSignal buffer 2
    pure [toInteger filled, toInteger deleted, toInteger member]
  print (toInteger device : toInteger page : image)
