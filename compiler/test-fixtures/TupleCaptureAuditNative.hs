-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts (Int(I#), Int#)
import TupleCaptureAudit

entries :: [(String, Int# -> Int#)]
entries = [("escaped", escaped), ("thunk", thunk), ("independent", independent), ("papReuse", papReuse),
  ("nested", nested), ("emptyCapture", emptyCapture), ("stateCapture", stateCapture), ("lazyCapture", lazyCapture)]

main :: IO ()
main = sequence_ [putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f n)))
  | (name, f) <- entries, x@(I# n) <- [minBound, -2147483649, -65, -1] ++ [0..31] ++ [maxBound]]
