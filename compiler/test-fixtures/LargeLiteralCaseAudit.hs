{-# LANGUAGE MagicHash, NegativeLiterals, UnboxedTuples #-}
-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module LargeLiteralCaseAudit where

import GHC.Exts

{-# NOINLINE largeInt #-}
largeInt :: Int# -> Int#
largeInt x = case x of
  -9223372036854775808# -> 101#
  -1001# -> 102#
  -81# -> 103#
  -71# -> 104#
  -61# -> 105#
  -51# -> 106#
  -41# -> 107#
  -31# -> 108#
  -21# -> 109#
  -11# -> 110#
  0# -> 111#
  12# -> 112#
  22# -> 113#
  32# -> 114#
  42# -> 115#
  52# -> 116#
  62# -> 117#
  72# -> 118#
  82# -> 119#
  1002# -> 120#
  9223372036854775807# -> 121#
  _ -> x +# 7#

{-# NOINLINE largeWord #-}
largeWord :: Word# -> (# Word#, Int# #)
largeWord x = case x of
  0## -> (# x, 201# #)
  3## -> (# x, 202# #)
  13## -> (# x, 203# #)
  23## -> (# x, 204# #)
  33## -> (# x, 205# #)
  43## -> (# x, 206# #)
  53## -> (# x, 207# #)
  63## -> (# x, 208# #)
  73## -> (# x, 209# #)
  83## -> (# x, 210# #)
  93## -> (# x, 211# #)
  103## -> (# x, 212# #)
  1003## -> (# x, 213# #)
  9223372036854775807## -> (# x, 214# #)
  9223372036854775808## -> (# x, 215# #)
  9223372036854775809## -> (# x, 216# #)
  18446744073709551612## -> (# x, 217# #)
  18446744073709551613## -> (# x, 218# #)
  18446744073709551614## -> (# x, 219# #)
  18446744073709551615## -> (# x, 220# #)
  _ -> (# x, -23# #)

{-# NOINLINE largeWordCheck #-}
largeWordCheck :: Word# -> Int#
largeWordCheck x = case largeWord x of (# value, tag #) -> word2Int# value +# tag

{-# NOINLINE largeLazy #-}
largeLazy :: Int -> Int#
largeLazy (I# x) = largeInt x

boxInt :: Int# -> Int
boxInt = I#

main :: IO ()
main = do
  mapM_ (\n@(I# x) -> putStrLn ("int\t" ++ show n ++ "\t" ++ show (I# (largeInt x))))
    [minBound, minBound+1, -1002, -1001, -81, -71, -61, -51, -41, -31, -21, -11,
     -1, 0, 1, 12, 22, 32, 42, 52, 62, 72, 82, 1002, 1003, maxBound-1, maxBound]
  mapM_ (\w@(W# x) -> putStrLn ("word\t" ++ show (fromIntegral w :: Int) ++ "\t" ++ show (I# (largeWordCheck x))))
    [0, 1, 3, 13, 23, 33, 43, 53, 63, 73, 83, 93, 103, 1003, 1004,
     9223372036854775806, 9223372036854775807, 9223372036854775808,
     9223372036854775809, 9223372036854775810, maxBound-4, maxBound-3,
     maxBound-2, maxBound-1, maxBound]
