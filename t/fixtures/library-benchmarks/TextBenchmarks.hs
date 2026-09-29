-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, OverloadedStrings #-}
-- Adapted from text-2.1.3 Benchmarks.DecodeUtf8 (StrictLength) and
-- Benchmarks.Search (Text); see UPSTREAM.md for sources and corpus provenance.
module TextBenchmarks (textDecodeUtf8, textSearch) where
import Data.Bits ((.&.))
import qualified Data.ByteString as B
import qualified Data.Text as T
import qualified Data.Text.Encoding as T
import GHC.Exts (Int(I#), Int#)
import System.IO.Unsafe (unsafePerformIO)

{-# NOINLINE source #-}
source :: B.ByteString
source = unsafePerformIO (B.readFile "corpus/russian.txt")

rawInputs :: [B.ByteString]
rawInputs = [B.replicate n 32 <> source | n <- [0..15]]

textInputs :: [T.Text]
textInputs = map T.decodeUtf8 rawInputs

textDecodeUtf8 :: Int# -> Int#
textDecodeUtf8 input =
  case T.length (T.decodeUtf8 (rawInputs !! (I# input .&. 15))) of
    I# result -> result

textSearch :: Int# -> Int#
textSearch input =
  case T.count "принимая" (textInputs !! (I# input .&. 15)) of
    I# result -> result
