-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module Main where
import GHC.Exts
import Data.Bits (finiteBitSize)
import Data.List (intercalate)
import SimdDoubleX2ByteArray

emit :: [String] -> Int -> IO ()
emit fields answer = putStrLn (intercalate "\t" (fields ++ [show answer]))

dispatch :: [String] -> IO ()
dispatch fields@[name, a, b, c] = case (read a, read b, read c) of
  (I# offset, I# x0, I# x1) -> emit fields (I# (case name of
    "vectorGraphIndexCase" -> vectorGraphIndexCase offset x0 x1
    "scalarGraphIndexCase" -> scalarGraphIndexCase offset x0 x1
    _ -> error "unknown graph index entry"))
dispatch fields@[name, a, b, c, d] = case (read a, read b, read c, read d) of
  (I# offset, I# x0, I# x1, I# selector) -> emit fields (I# (case name of
    "vectorUnitCase" -> vectorUnitCase offset x0 x1 selector
    "vectorIndexCase" -> vectorIndexCase offset x0 x1 selector
    "vectorReadCase" -> vectorReadCase offset x0 x1 selector
    "vectorWriteCase" -> vectorWriteCase offset x0 x1 selector
    "vectorGraphStoreCase" -> vectorGraphStoreCase offset x0 x1 selector
    "scalarUnitCase" -> scalarUnitCase offset x0 x1 selector
    "scalarIndexCase" -> scalarIndexCase offset x0 x1 selector
    "scalarReadCase" -> scalarReadCase offset x0 x1 selector
    "scalarWriteCase" -> scalarWriteCase offset x0 x1 selector
    "scalarGraphStoreCase" -> scalarGraphStoreCase offset x0 x1 selector
    _ -> error "unknown raw or store entry"))
dispatch _ = error "invalid input"

main :: IO ()
main = if finiteBitSize (0 :: Int) /= 64 then error "Requires 64-bit Int"
       else getContents >>= mapM_ (dispatch . words) . lines
