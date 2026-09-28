-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; declared foreign symbols required at link/run time
--
-- Native GHC observer for the original memcpy fixture.
module Main (main) where

import Control.Monad (forM_)
import Data.List (intercalate)
import Data.Word (Word8)
import Foreign.C.Types (CSize(..))
import Foreign.Marshal.Array (allocaArray, peekArray, pokeArray)
import Foreign.Ptr (Ptr, plusPtr)

-- The exact pointer/size/result ABI of the retained ram-0.22.1 FCall.
-- This native oracle tests libc semantics, not the whole ram package closure.
foreign import ccall unsafe "memcpy"
  memcpy :: Ptr Word8 -> Ptr Word8 -> CSize -> IO (Ptr Word8)

main :: IO ()
main = allocaArray 16 $ \source -> allocaArray 16 $ \destination ->
  forM_ [0, 1, 3] $ \sourceOffset -> forM_ [0, 1, 3] $ \destinationOffset ->
    forM_ [0, 1, 5, 13] $ \count -> do
      pokeArray source ([16..31] :: [Word8])
      pokeArray destination (replicate 16 (0 :: Word8))
      result <- memcpy (destination `plusPtr` destinationOffset)
        (source `plusPtr` sourceOffset) (fromIntegral (count :: Int))
      copied <- peekArray 16 destination
      original <- peekArray 16 source
      putStrLn $ intercalate "\t" [show sourceOffset, show destinationOffset, show count,
        show (result == destination `plusPtr` destinationOffset),
        intercalate "," (map show copied), intercalate "," (map show original)]
