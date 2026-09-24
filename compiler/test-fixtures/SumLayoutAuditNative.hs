-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import SumLayoutAudit
emit :: String -> (Int# -> Int#) -> Int -> IO ()
emit n f x@(I# a) = putStrLn (n ++ "\t" ++ show x ++ "\t" ++ show (I# (f a)))
main = sequence_ [emit n f x | (n,f) <- [("sumCase",sumCase),("directCase",directCase),
  ("nestedCase",nestedCase),("lazyCase",lazyCase),("zeroCase",zeroCase),("unitCase",unitCase),
  ("boxedKindsCase",boxedKindsCase),("floatDoubleCase",floatDoubleCase),
  ("narrowWideCase",narrowWideCase),("threeWayCase",threeWayCase)], x <- [minBound,-2147483649,-2147483648,-5,-1,0,1,7,2147483647,2147483648,4294967295,4294967296,maxBound]]
