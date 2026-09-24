-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import SimdInt64X2
main :: IO ()
main = mapM_ run [(name, f, a, b) | (name, f) <- entries, a <- inputs, b <- inputs]
  where
    entries = [("vectorCase", vectorCase), ("subtractCase", subtractCase), ("branchCase", branchCase)]
    inputs = [minBound, -3000000001, -1, 0, 1, 9000000003, maxBound] :: [Int]
    run (name, f, a@(I# x), b@(I# y)) = putStrLn (name ++ "\t" ++ show a ++ "\t" ++ show b ++ "\t" ++ show (I# (f x y)))
