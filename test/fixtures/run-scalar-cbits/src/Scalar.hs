-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, ForeignFunctionInterface #-}
module Scalar (scalarInt32, scalarInt64, scalarFloat, scalarDouble, scalarMixed) where
import GHC.Exts
import GHC.Int (Int32(..), Int64(..))
import GHC.IO (IO(..))
-- This spelling also names a managed runtime adapter. Its genuine package unit
-- and Int32 ABI must select this component's definition instead.
foreign import ccall unsafe "thc_io_v1_close" c_i32 :: Int32 -> IO Int32
foreign import ccall unsafe "scalar_i64" c_i64 :: Int64 -> IO Int64
foreign import ccall unsafe "scalar_float" c_float :: Float -> IO Float
foreign import ccall unsafe "scalar_double" c_double :: Double -> IO Double
foreign import ccall unsafe "scalar_mixed" c_mixed :: Int32 -> Int64 -> Float -> Double -> IO Int64
run :: IO a -> a
run (IO f) = case runRW# f of (# _, value #) -> value
{-# NOINLINE scalarInt32 #-}
scalarInt32 :: Int# -> Int#
scalarInt32 x = case run (c_i32 (I32# (intToInt32# x))) of I32# y -> int32ToInt# y
{-# NOINLINE scalarInt64 #-}
scalarInt64 :: Int# -> Int#
scalarInt64 x = case run (c_i64 (I64# (intToInt64# x))) of I64# y -> int64ToInt# y
{-# NOINLINE scalarFloat #-}
scalarFloat :: Int# -> Int#
scalarFloat x = case run (c_float (F# (castWord32ToFloat# (wordToWord32# (int2Word# x))))) of
  F# y -> word2Int# (word32ToWord# (castFloatToWord32# y))
{-# NOINLINE scalarDouble #-}
scalarDouble :: Int# -> Int#
scalarDouble x = case run (c_double (D# (castWord64ToDouble# (wordToWord64# (int2Word# x))))) of
  D# y -> word2Int# (word64ToWord# (castDoubleToWord64# y))
{-# NOINLINE scalarMixed #-}
scalarMixed :: Int# -> Int#
scalarMixed x = case run (c_mixed (I32# (intToInt32# x)) (I64# (intToInt64# 4294967299#))
                         (F# (int2Float# x)) (D# (int2Double# (negateInt# x)))) of
  I64# y -> int64ToInt# y
