-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Monad (forM)
import Data.Word (Word8)
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Storable (peekByteOff, pokeByteOff)
import GHC.Exts (Int(I#))
import GHC.Ptr (minusPtr, plusPtr)
import qualified GHC.Internal.System.Posix.Internals as P
import OriginalTermiosAudit

main :: IO ()
main = do
  let size = I# (originalTermiosSize 0#)
      constants = [size, I# (originalEcho 0#), I# (originalIcanon 0#), I# (originalVmin 0#),
        I# (originalVtime 0#), I# (originalTcsanow 0#)]
      patterns = [0,1,255,2147483648,4294967295,3735928559 :: Word]
  rows <- allocaBytes (size + 16) $ \storage -> forM patterns $ \pattern -> do
    fillBytes storage 90 (size + 16)
    let buffer = storage `plusPtr` 8
    -- Real IO sequencing matters for repeated reads of one mutable image:
    -- the pure runRW observer used by JVM entries can be floated by native GHC.
    P.poke_c_lflag buffer (fromIntegral pattern)
    value <- P.c_lflag buffer
    cc <- P.ptr_c_cc buffer
    pokeByteOff cc (I# (originalVmin 0#)) (171 :: Word8)
    pokeByteOff cc (I# (originalVtime 0#)) (205 :: Word8)
    bytes <- mapM (peekByteOff storage) [0..size+15] :: IO [Word8]
    pure (toInteger pattern, 0 :: Int, toInteger value, cc `minusPtr` buffer, bytes)
  print (constants, rows)
