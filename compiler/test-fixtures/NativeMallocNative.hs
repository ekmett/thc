-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Exception (bracket)
import Control.Monad (forM_)
import Data.Word (Word8, Word64)
import Foreign.Marshal.Alloc (mallocBytes, free)
import Foreign.Marshal.Utils (copyBytes)
import Foreign.Ptr (nullPtr)
import Foreign.Storable (peekByteOff, pokeByteOff)

main :: IO ()
main = do
  free nullPtr
  forM_ ([0, 1, 2, 197] :: [Word64]) $ \seed ->
    bracket (mallocBytes 24) free $ \base ->
    bracket (mallocBytes 24) free $ \copy -> do
      forM_ [0..23] $ \i -> pokeByteOff base i (0 :: Word8)
      pokeByteOff base 7 (fromIntegral seed :: Word8)
      pokeByteOff base 8 (seed * 257)
      copyBytes copy base 24
      byte <- peekByteOff base 7 :: IO Word8
      word <- peekByteOff base 8 :: IO Word64
      copied <- peekByteOff copy 7 :: IO Word8
      putStrLn (unwords [show seed, show byte, show word, show copied])
