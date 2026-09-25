-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}
module ForeignExportSignatures where

import Data.Int (Int8, Int32)
import Data.Word (Word16)
import Foreign.Ptr (FunPtr)

newtype Count = Count Int32

foreign export ccall "thc_export_pure" pureValue :: Int8 -> Word16 -> Float -> Double -> Double
pureValue :: Int8 -> Word16 -> Float -> Double -> Double
pureValue a b c d = fromIntegral a + fromIntegral b + realToFrac c + d

foreign export ccall "thc_export_count" countValue :: Count -> IO Count
countValue :: Count -> IO Count
countValue = pure

foreign export ccall "thc_export_alias_one" aliasValue :: Int32 -> Int32
foreign export ccall "thc_export_alias_two" aliasValue :: Int32 -> Int32
aliasValue :: Int32 -> Int32
aliasValue = id

-- A dynamic wrapper is an import and must not enter the static inventory.
foreign import ccall "wrapper" makeCallback :: (Int32 -> IO Int32) -> IO (FunPtr (Int32 -> IO Int32))
