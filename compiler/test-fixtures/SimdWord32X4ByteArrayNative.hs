-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where

import GHC.Exts
import Data.Bits (finiteBitSize)
import Data.List (intercalate)
import SimdWord32X4ByteArray

emit :: [String] -> Int -> IO ()
emit fields answer = putStrLn (intercalate "\t" (fields ++ [show answer]))

dispatch :: [String] -> IO ()
dispatch fields@[name, a, b] = case (read a, read b) of
  (I# offset, I# seed) -> emit fields (I# (case name of
    "vectorUnitCase" -> vectorUnitCase offset seed
    "scalarUnitCase" -> scalarUnitCase offset seed
    _ -> error "unknown alias entry"))
dispatch fields@[name, a, b, c, d, e] = case (read a, read b, read c, read d, read e) of
  (I# offset, I# x0, I# x1, I# x2, I# x3) -> emit fields (I# (case name of
    "vectorIndexCase" -> vectorIndexCase offset x0 x1 x2 x3
    "scalarIndexCase" -> scalarIndexCase offset x0 x1 x2 x3
    "vectorReadCase" -> vectorReadCase offset x0 x1 x2 x3
    "scalarReadCase" -> scalarReadCase offset x0 x1 x2 x3
    _ -> error "unknown load entry"))
dispatch fields@[name, a, b, c, d, e, f] = case (read a, read b, read c, read d, read e, read f) of
  (I# offset, I# x0, I# x1, I# x2, I# x3, I# byte) -> emit fields (I# (case name of
    "vectorWriteCase" -> vectorWriteCase offset x0 x1 x2 x3 byte
    "scalarWriteCase" -> scalarWriteCase offset x0 x1 x2 x3 byte
    "vectorStoreCase" -> vectorStoreCase offset x0 x1 x2 x3 byte
    "scalarStoreCase" -> scalarStoreCase offset x0 x1 x2 x3 byte
    _ -> error "unknown store entry"))
dispatch _ = error "invalid input"

main :: IO ()
main = if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int"
       else getContents >>= mapM_ (dispatch . words) . lines
