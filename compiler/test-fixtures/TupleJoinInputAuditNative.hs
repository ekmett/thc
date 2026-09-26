-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), Int#)
import TupleJoinInputAudit

entries :: [(String, Int# -> Int#)]
entries = [("forward", forward), ("recursiveSwap", recursiveSwap), ("nested", nested),
  ("originalRoundTo", originalRoundTo), ("emptyRetry", emptyRetry), ("emptyException", emptyException)]

main :: IO ()
main = sequence_ [putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f n)))
  | (name, f) <- entries, x@(I# n) <- [minBound, -2147483649, -65, -1] ++ [0..31] ++ [maxBound]]
