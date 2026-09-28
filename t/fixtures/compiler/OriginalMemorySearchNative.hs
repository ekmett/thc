-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the original memory search fixture.
module Main where

import Control.Exception (evaluate)
import Data.Word (Word8)
import Foreign.Marshal.Array (withArray)
import Foreign.Ptr (nullPtr, plusPtr, minusPtr)
import GHC.Exts (Int(..))
import GHC.Ptr (Ptr(..))
import OriginalMemorySearchAudit

type Request = (String,[Word8],Int,[Word8],Int,Int,Int)

requests :: [Request]
requests =
  [ ("originalCompare", left, offset, right, offset, 0, count)
  | seed <- [0,127,255], count <- [0,1,15,16,17,31,32,33,64,65,129], offset <- [0,3],
    changed <- [-1,0,count-1,count],
    let left = take 140 [fromIntegral (seed + index * 37) | index <- [0..] :: [Int]],
    let right = [if index == offset + changed && changed >= 0 then value + 1 else value
                | (index,value) <- zip [0..] left] ] ++
  [ ("originalFind", source, offset, [], 0, needle, count)
  | source <- [[0,128,255,42,0,42,127,128,255], [255,255,255,255,255,255,255,255,255]],
    offset <- [0,2], count <- [0,1,4,7], needle <- [-1,0,42,127,128,255,256,511] ]

main :: IO ()
main = mapM observe requests >>= print
  where
    observe row@(entry,left,leftOffset,right,rightOffset,needle,count) =
      withArray left $ \leftBase -> withArray right $ \rightBase ->
        case (leftBase `plusPtr` leftOffset, rightBase `plusPtr` rightOffset, needle, count) of
          (Ptr a, Ptr b, I# value, I# size) -> do
            result <- case entry of
              "originalCompare" -> signum <$> evaluate (I# (originalCompare a b size))
              "originalFind" -> do
                pointer <- evaluate (Ptr (originalFind a value size))
                pure (if pointer == nullPtr then -1 else pointer `minusPtr` leftBase)
              _ -> fail "Unknown memory search request"
            pure (row,result)
