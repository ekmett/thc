-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE AllowAmbiguousTypes, ScopedTypeVariables, TypeApplications #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC with the declared language extensions
--
-- Native GHC observer for the original primitive fixture.
module Main (main) where

import Control.Monad (forM_, when)
import Data.Int
import Data.List (intercalate)
import Data.Primitive.ByteArray
import Data.Primitive.Ptr (setPtr)
import Data.Primitive.Types (Prim, sizeOf)
import Data.Word
import Foreign.Marshal.Alloc (allocaBytes)
import Foreign.Marshal.Utils (fillBytes)
import Foreign.Ptr (castPtr, plusPtr)
import Foreign.Storable (peekByteOff)
import Numeric (showHex)

-- Original package APIs only: compare the actual memory after both managed
-- array and address setters, including zero length and untouched boundaries.
check :: forall a. (Prim a, Integral a) => String -> IO ()
check rep = forM_ [0,1,-1,-128,128,0x123456789abcdef0] $ \integer ->
  forM_ [0,2] $ \offset -> forM_ [0,1,7] $ \count -> do
    let value = fromInteger integer :: a
        width = sizeOf value
        bytes = (offset + count + 3) * width
        emit carrier contents = putStrLn $ intercalate "\t"
          [rep,carrier,show offset,show count,show (toInteger value),concatMap hex contents]
    mutable <- newByteArray bytes
    setByteArray mutable 0 bytes (0xa5 :: Word8)
    setByteArray mutable offset count value
    frozen <- unsafeFreezeByteArray mutable
    emit "MutableByteArray#" [indexByteArray frozen i :: Word8 | i <- [0 .. bytes-1]]
    allocaBytes bytes $ \pointer -> do
      fillBytes pointer 0xa5 bytes
      setPtr (castPtr (pointer `plusPtr` (offset * width))) count value
      contents <- mapM (peekByteOff pointer) [0 .. bytes-1]
      emit "AddrRep" (contents :: [Word8])
  where hex value = let text = showHex value "" in if length text == 1 then '0':text else text

main :: IO ()
main = do
  when (sizeOf (0 :: Word) /= 8) (fail "original primitive fixture requires the selected 64-bit target")
  check @Int "IntRep"; check @Word "WordRep"
  check @Int8 "Int8Rep"; check @Word8 "Word8Rep"
  check @Int16 "Int16Rep"; check @Word16 "Word16Rep"
  check @Int32 "Int32Rep"; check @Word32 "Word32Rep"
  check @Int64 "Int64Rep"; check @Word64 "Word64Rep"
