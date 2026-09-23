{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int(I#))
import qualified SimdInt32X4ByteArray as P

seeds :: [Int]
seeds = [minBound, -4294967297, -2147483649, -2147483648, -2147483647,
         -1, 0, 1, 127, 128, 255, 256, 2147483646, 2147483647,
         2147483648, 4294967295, 4294967296, maxBound]

main :: IO ()
main = do
  mapM_ (\(offset@(I# i), seed@(I# x)) ->
    putStrLn ("vectorUnitCase\t" ++ show offset ++ "\t" ++ show seed ++ "\t" ++ show (I# (P.vectorUnitCase i x))))
    [(offset, seed) | offset <- [0..3], seed <- seeds]
  mapM_ (\(offset@(I# i), seed@(I# x)) ->
    putStrLn ("scalarUnitCase\t" ++ show offset ++ "\t" ++ show seed ++ "\t" ++ show (I# (P.scalarUnitCase i x))))
    [(offset, seed) | offset <- [0..12], seed <- seeds]
