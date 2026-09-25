-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where

import Control.Monad (forM)
import Data.Word (Word8)
import Foreign.C.Error (Errno(..), getErrno, resetErrno)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (castPtr, plusPtr)
import Foreign.Storable (peekByteOff)
import qualified GHC.Internal.System.Posix.Internals as P

main :: IO ()
main = do
  let size = P.sizeof_sigset_t
      cases = [(operation, fill, signal) | fill <- [0,90,165,255], operation <- [0,1],
        signal <- if operation == 0 then [0] else [-2147483648,-1] ++ [0..128] ++ [2147483647]]
  rows <- allocaBytes (size + 16) $ \storage -> forM cases $ \(operation, fill, signal) -> do
    fillBytes storage 77 (size + 16)
    let image = castPtr (storage `plusPtr` 8)
    fillBytes image fill size
    resetErrno
    -- Keep real IO ordering: a pure runRW observer can be floated/shared.
    status <- if operation == 0 then P.c_sigemptyset image else P.c_sigaddset image (fromIntegral signal)
    Errno errno <- getErrno
    bytes <- forM [0..size+15] $ \i -> fromIntegral <$> (peekByteOff storage i :: IO Word8)
    pure (operation, fill, signal, fromIntegral status :: Int, fromIntegral errno :: Int, bytes :: [Int])
  print (size, rows)
