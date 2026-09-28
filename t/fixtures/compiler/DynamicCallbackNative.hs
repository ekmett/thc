-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where
import qualified DynamicCallback
import Control.Exception (mask_)
import Data.Int (Int32)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Ptr (minusPtr)
import Foreign.Storable (peek, peekByteOff, poke)
main :: IO ()
main = do
  DynamicCallback.run 5 >>= print
  mask_ $ allocaBytes 8 $ \pointer -> do
    poke pointer (7 :: Int32)
    callback <- DynamicCallback.makePointer
    echoed <- DynamicCallback.echoPointer callback
    returned <- DynamicCallback.callPointer echoed pointer
    value <- peek pointer
    state <- peekByteOff pointer 4 :: IO Int32
    DynamicCallback.releasePointer callback
    print (value, state, returned `minusPtr` pointer)
