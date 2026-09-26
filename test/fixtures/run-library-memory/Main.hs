{-# LANGUAGE ScopedTypeVariables #-}
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Monad.ST (ST, runST)
import Data.Array.ST (STUArray, freeze, getElems, newListArray, thaw, writeArray)
import Data.Array.Unboxed (UArray, elems)
import qualified Data.ByteString as Bytes
import Data.Word (Word8)
import Foreign.Marshal.Array (withArray)
import System.IO (hFlush, stdout)

arrayCopies :: ([Word8], [Word8])
arrayCopies = runST action
  where
    action :: forall s. ST s ([Word8], [Word8])
    action = do
      original <- newListArray (0, 3) [2, 3, 5, 7] :: ST s (STUArray s Int Word8)
      frozen <- freeze original :: ST s (UArray Int Word8)
      writeArray original 0 99
      copied <- thaw frozen :: ST s (STUArray s Int Word8)
      writeArray copied 1 88
      result <- getElems copied
      pure (elems frozen, result)

main :: IO ()
main = do
  print arrayCopies
  -- bytestring's original CString import returns CSize, not GHC's Int# variant.
  bytes <- withArray [65, 66, 0, 67] Bytes.packCString
  print (Bytes.unpack bytes)
  hFlush stdout
