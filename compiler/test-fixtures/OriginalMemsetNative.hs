-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where

import Control.Exception (evaluate)
import Data.Word (Word8)
import Foreign.Marshal.Array (withArray, peekArray)
import Foreign.Ptr (plusPtr, minusPtr)
import GHC.Exts (Int(..))
import GHC.Ptr (Ptr(..))
import OriginalMemsetAudit

type Request = (Int,Int,Int,[Word8])

requests :: [Request]
requests =
  [ (offset,value,count,take 40 [fromIntegral (index * 37 + 19) | index <- [0..] :: [Int]])
  | offset <- [0,3], count <- [0,1,7,8,15,16,17,31,32],
    value <- [-2147483648,-257,-1,0,1,127,128,255,256,511,2147483647] ]

main :: IO ()
main = mapM observe requests >>= print
  where
    observe row@(offset,value,count,bytes) = withArray bytes $ \base ->
      case (base `plusPtr` offset, value, count) of
        (Ptr address, I# byte, I# size) -> do
          returned <- evaluate (Ptr (originalFill address byte size))
          after <- peekArray (length bytes) base
          pure (row,(returned `minusPtr` base,after))
