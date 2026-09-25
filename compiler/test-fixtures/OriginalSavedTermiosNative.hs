-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where

import Control.Exception (bracket)
import Control.Monad (forM)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Ptr (nullPtr, plusPtr, minusPtr)
import qualified GHC.Internal.System.Posix.Internals as P

main :: IO ()
main = do
  rows <- allocaBytes 8 $ \storage ->
    -- Restore the RTS's original roots before temporary storage expires,
    -- including on an exception. No terminal bytes are ever read or changed.
    bracket (mapM P.get_saved_termios [0,1,2])
      (mapM_ (uncurry P.set_saved_termios) . zip [0,1,2]) $ \_ -> do
        let pointer offset = if offset == -1 then nullPtr else storage `plusPtr` offset
            offsetOf pointerValue = if pointerValue == nullPtr then -1 else pointerValue `minusPtr` storage
        fmap concat $ forM [-2147483648,-1,0,1,2,3,2147483647] $ \fd ->
          forM [-1,0,3,8] $ \offset -> do
            mapM_ (\slot -> P.set_saved_termios slot nullPtr) [0,1,2]
            initial <- P.get_saved_termios fd
            P.set_saved_termios fd (pointer offset)
            first <- P.get_saved_termios fd
            let next = if offset == -1 then 5 else 8 - offset
            P.set_saved_termios fd (pointer next)
            replaced <- P.get_saved_termios fd
            other <- mapM P.get_saved_termios (filter (/= fd) [0,1,2])
            P.set_saved_termios fd nullPtr
            cleared <- P.get_saved_termios fd
            pure [fromIntegral fd, offset, offsetOf first, next, offsetOf replaced,
                  if initial == nullPtr && cleared == nullPtr && all (== nullPtr) other then 1 else 0]
  print rows
