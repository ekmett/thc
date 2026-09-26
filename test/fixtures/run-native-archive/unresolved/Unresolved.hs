-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, MagicHash, UnliftedFFITypes #-}
module Unresolved (process, stdio) where
import GHC.Exts (Int#)
import Foreign.C.String (CString, withCString)
import Foreign.C.Types (CInt(..))
import Foreign.Ptr (Ptr, nullPtr)
foreign import ccall unsafe "archive_process" process :: Int# -> Int#
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
