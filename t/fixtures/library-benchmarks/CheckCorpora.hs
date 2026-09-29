-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import Control.Monad (forM_, unless)
import GHC.Exts (Int(I#))
import qualified AesonBenchmarks as A
import qualified ByteStringBenchmarks as B
import qualified TextBenchmarks as T
main :: IO ()
main = forM_ [0..15] $ \n -> case n of
  I# input -> do
    check "readInt" n (I# (B.bytestringReadInt input)) (-128 - 256*n)
    check "decodeUtf8" n (I# (T.textDecodeUtf8 input)) (2771535+n)
    check "search" n (I# (T.textSearch input)) 1398
    check "decodeValue" n (I# (A.aesonDecodeValue input)) 11
  where
    check name n actual expected = unless (actual == expected) $
      error (name ++ ": corpus " ++ show n ++ " expected " ++ show expected
             ++ ", got " ++ show actual)
