-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import AggregateFrontier
import GHC.Exts (Int(I#), Int#)

-- These results are a native oracle for a future implementation. They never
-- turn rejection by THC into an oracle pass or a supported corpus entry.
entries :: [(String, Int# -> Int#)]
entries = [("tupleOutstanding", tupleOutstanding), ("tupleZeroLazy", tupleZeroLazy),
           ("sumPayload", sumPayload), ("sumZeroLazy", sumZeroLazy),
           ("coldTuple", coldTuple), ("coldSum", coldSum)]

inputs :: [Int]
inputs = [minBound, -4097, -1, 0, 1, 4097, 3000000000, maxBound]

main :: IO ()
main = mapM_ (\(name, f) -> mapM_ (\(I# x) ->
  putStrLn (name ++ "\t" ++ show (I# x) ++ "\t" ++ show (I# (f x)))) inputs) entries
