-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE ForeignFunctionInterface #-}
module Main where

import Control.Monad (forM)
import Data.Word (Word8)
import Foreign.C.Types (CChar, CInt(..), CSize(..))
import Foreign.Marshal.Array (peekArray, withArray)
import Foreign.Ptr (Ptr, castPtr)
import GHC.Internal.Foreign.C.Error (Errno(..), errnoToIOError)
import System.IO.Error (ioeGetErrorString)

-- The installed ghc-internal implementation calls its original
-- base_strerror_r, then peeks the caller-owned C buffer into a String.
-- Probe that same C symbol directly to retain status and every buffer byte,
-- including writes on error and untouched canaries after the message.
foreign import ccall safe "base_strerror_r"
  cStrerror :: CInt -> Ptr CChar -> CSize -> IO CInt

main :: IO ()
main = do
  let messages = [(number, ioeGetErrorString (errnoToIOError "fixture"
        (Errno (fromIntegral number)) Nothing Nothing)) | number <- [2 :: Int, 22]]
  raw <- forM [(22 :: Int, 512), (999999, 512), (22, 4), (22, 8)] $ \(number, size) ->
    withArray (replicate size (0x55 :: Word8)) $ \buffer -> do
      status <- cStrerror (fromIntegral number) (castPtr buffer) (fromIntegral size)
      bytes <- peekArray size buffer
      pure (number, size, fromIntegral status :: Int, map (fromIntegral :: Word8 -> Int) bytes)
  print (messages, raw)
