-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes, UnboxedTuples #-}

-- |
-- Module      : Unresolved
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; declared foreign symbols required at link/run time
--
-- Module for the @run-native-archive@ integration fixture.
module Unresolved (process, stdio, partialProbe#, throughGlobal) where
import GHC.Exts (Int(I#), Int#, runRW#, (+#), (*#))
import GHC.IO (IO(..))
import Foreign.C.String (CString, withCString)
import Foreign.C.Types (CInt(..))
import Foreign.Ptr (Ptr, nullPtr)
foreign import ccall unsafe "archive_process" process :: Int# -> Int#
foreign import ccall unsafe "archive_through_global" throughGlobal :: Int# -> Int#
foreign import ccall unsafe "archive_partial_add" partialAdd :: Int# -> IO Int
foreign import ccall unsafe "archive_partial_read" partialRead :: Int# -> IO Int
{-# NOINLINE partialProbe# #-}
partialProbe# :: Int# -> Int#
partialProbe# value = runRW# $ \s0 -> case partialAdd value of
  IO add -> case add s0 of
    (# s1, I# first #) -> case partialRead 0# of
      IO readState -> case readState s1 of
        (# _, I# second #) -> first *# 100# +# second
-- Same opaque FILE* declarations as original libyaml, without an injected
-- Rts.h/stdio.h declaration namespace in the generated direct caller TU.
foreign import ccall unsafe "archive_descriptor" descriptor :: IO CInt
foreign import ccall unsafe "fdopen" openStream :: CInt -> CString -> IO (Ptr ())
foreign import ccall unsafe "fclose" closeStream :: Ptr () -> IO CInt
stdio :: IO Bool
stdio = withCString "r" $ \mode -> do
  fd <- descriptor
  stream <- openStream fd mode
  if stream == nullPtr then pure False else (== 0) <$> closeStream stream
