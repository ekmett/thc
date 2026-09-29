-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-FileCopyrightText: 2021 Viktor Dukhovni
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE BangPatterns, MagicHash #-}
-- | Adapted from bytestring-0.12.2.0 bench/BenchReadInt.hs, Strict/ReadInt.
-- The original parser loop and 256 mixed-sign boundary-number input are retained.
module ByteStringBenchmarks (bytestringReadInt) where

import Data.Bits ((.&.))
import qualified Data.ByteString.Builder as B
import qualified Data.ByteString.Char8 as S
import qualified Data.ByteString.Lazy as L
import GHC.Exts (Int(I#), Int#)

-- Prepared raw inputs, not parsed results. The dynamic selector keeps parsing
-- inside each call; all sixteen corpora are exercised before measurement.
corpora :: [S.ByteString]
corpora = map corpus [0..15]
  where
    corpus offset = L.toStrict $ B.toLazyByteString $
      mconcat [B.intDec (i + 128) <> B.char8 ' ' | i <- [n-255..n]]
      where n = maxBound - offset :: Int

-- Original upstream loopS, specialized only by its ordinary Int type.
loopS :: (S.ByteString -> Maybe (Int, S.ByteString)) -> S.ByteString -> Int
loopS rd = go 0
  where
    go !acc !bs = case rd bs of
      Just (i, t) -> case S.uncons t of
        Just (_, t') -> go (acc + i) t'
        Nothing -> acc + i
      Nothing -> acc

-- | Parse one dynamically selected upstream corpus and sum all 256 integers.
bytestringReadInt :: Int# -> Int#
bytestringReadInt input =
  case loopS S.readInt (corpora !! (I# input .&. 15)) of I# result -> result
