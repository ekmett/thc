-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, Unsafe #-}

-- | Private versioned FFI boundary. Raw selectors and pointers are hazardous;
-- applications should use the typed service modules, not this implementation.
module THC.Internal.RuntimeABI
  ( Availability(..), query, queryInt, queryWord64, queryEnum, queryText, control, traceCall
  ) where

import Data.Char (chr)
import Data.Int (Int64)
import Data.Word (Word8, Word64)
import Foreign.C.Types (CInt(..), CLLong(..))
import Foreign.Ptr (Ptr)
import THC.Runtime.Types (Availability(..))

foreign import ccall unsafe "thc_runtime_v1_query"
  queryCode :: CInt -> CLLong -> CLLong -> IO CLLong
foreign import ccall unsafe "thc_runtime_v1_control"
  controlCode :: CInt -> CLLong -> IO CLLong
foreign import ccall unsafe "thc_runtime_v1_trace"
  traceCode :: CInt -> CLLong -> Ptr Word8 -> CLLong -> IO CLLong

decode :: CLLong -> IO (Availability Int64)
decode value = case value of
  -1 -> pure Unsupported
  -2 -> pure Disabled
  -3 -> pure Denied
  -4 -> pure Unavailable
  _ | value >= 0 -> pure (Available (fromIntegral value))
    | otherwise -> fail "THC runtime service returned an invalid status"

-- Keep the primitive result concrete. An Integral dictionary would retain
-- unrelated Real/toRational/Integer machinery in an application's Core closure.
query :: Int -> Int64 -> Int64 -> IO (Availability Int64)
query selector index detail =
  queryCode (fromIntegral selector) (fromIntegral index) (fromIntegral detail) >>= decode

queryInt :: Int -> Int64 -> Int64 -> IO (Availability Int)
queryInt selector index detail = fmap (fmap fromIntegral) (query selector index detail)

queryWord64 :: Int -> Int64 -> Int64 -> IO (Availability Word64)
queryWord64 selector index detail = fmap (fmap fromIntegral) (query selector index detail)

queryEnum :: Int -> [(Int64, a)] -> IO (Availability a)
queryEnum selector choices = do
  result <- query selector 0 0
  case result of
    Available code -> case lookup code choices of
      Just value -> pure (Available value)
      Nothing -> fail "THC runtime service returned an invalid enumeration"
    Unsupported -> pure Unsupported
    Disabled -> pure Disabled
    Denied -> pure Denied
    Unavailable -> pure Unavailable

-- Text indices are Unicode codepoints, not UTF-16 code units.
queryText :: Int -> Int64 -> IO (Availability String)
queryText selector index = do
  size <- query selector index (-1)
  case size of
    Available count -> readCharacters 0 count []
    Unsupported -> pure Unsupported
    Disabled -> pure Disabled
    Denied -> pure Denied
    Unavailable -> pure Unavailable
  where
    readCharacters offset count reversed
      | offset == count = pure (Available (reverse reversed))
      | otherwise = do
          result <- queryInt selector index offset
          case result of
            Available point
              | point <= 0x10ffff && not (point >= 0xd800 && point <= 0xdfff) ->
                  readCharacters (offset + 1) count (chr point : reversed)
              | otherwise -> fail "THC runtime service returned an invalid Unicode codepoint"
            Unsupported -> pure Unsupported
            Disabled -> pure Disabled
            Denied -> pure Denied
            Unavailable -> pure Unavailable

control :: Int -> Int64 -> IO (Availability ())
control selector setting = fmap (fmap (const ())) $
  controlCode (fromIntegral selector) (fromIntegral setting) >>= decode

traceCall :: Int -> Int64 -> Ptr Word8 -> Int -> IO (Availability Int64)
traceCall operation token pointer count =
  traceCode (fromIntegral operation) (fromIntegral token) pointer (fromIntegral count) >>= decode
