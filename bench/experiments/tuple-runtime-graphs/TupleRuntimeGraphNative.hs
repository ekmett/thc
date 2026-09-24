-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), Int#)
import TupleRuntimeGraph

inputs :: [(Int, Int)]
inputs = [(0,0),(1,-1),(-1,1),(3000000001,9000000043),(-9000000007,4000000011),
          (17,29),(29,17),(maxBound,minBound),(minBound,maxBound),(maxBound,maxBound),(minBound,minBound)]

row :: String -> (Int# -> Int# -> Int#) -> (Int,Int) -> IO ()
row name f (x@(I# a),y@(I# b)) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show y ++ "\t" ++ show (I# (f a b)))

main :: IO ()
main = do
  mapM_ (row "pairCase" pairCase) inputs
  mapM_ (row "forwardedCase" forwardedCase) inputs
  mapM_ (row "outstandingCase" outstandingCase) inputs
  mapM_ (row "mixedCase" mixedCase) inputs
  mapM_ (row "lazyMixedCase" lazyMixedCase) inputs
