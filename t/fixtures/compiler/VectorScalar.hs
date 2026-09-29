-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main where

import Control.Monad (forM_)
import Data.Bits (testBit)
import Data.Int (Int32)

-- An ordinary native-GHC scalar oracle, independent of THC and the Vector API.
main :: IO ()
main = forM_ [1,2,4,8,16,32,64] $ \lanes ->
  forM_ [0,1,3,5,13,21,65] $ \count -> do
    let a = [fromIntegral i * 0.5 - 5 :: Float | i <- [0..count-1]]
        b = [fromIntegral (i `mod` 7) + 0.25 :: Float | i <- [0..count-1]]
        ints = [fromIntegral (i * 7 - 33) :: Int32 | i <- [0..count-1]]
        selected = zipWith (\i x -> if x > 4 && testBit (0xa55a :: Integer) (i `mod` lanes) then negate x else x) [0..] ints
        reversed = concatMap reverse (blocks lanes (take (count - count `mod` lanes) ints))
          ++ drop (count - count `mod` lanes) ints
    putStrLn $ show lanes ++ "\t" ++ show count ++ "\t" ++ show (zipWith (*) a b)
      ++ "\t" ++ show (zipWith (\x y -> negate (x*x + y*y)) a b)
      ++ "\t" ++ show selected ++ "\t" ++ show reversed
  where
    blocks _ [] = []
    blocks n xs = take n xs : blocks n (drop n xs)
