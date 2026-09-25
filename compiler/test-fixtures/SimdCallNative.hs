-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts (Int (I#), Int#)
import SimdCallAudit

entry :: String -> Int# -> Int#
entry "directCase" = directCase
entry "papCase" = papCase
entry "nestedTupleCase" = nestedTupleCase
entry "joinCase" = joinCase
entry "overCase" = overCase
entry _ = error "Unknown SIMD call entry"

emit :: String -> Int -> IO ()
emit name input@(I# x) =
  putStrLn (name ++ "\t" ++ show input ++ "\t" ++ show (I# (entry name x)))

main :: IO ()
main = getContents >>= mapM_ (\line -> case words line of
  [name,x] -> emit name (read x)
  _ -> error "Invalid SIMD call request") . lines
