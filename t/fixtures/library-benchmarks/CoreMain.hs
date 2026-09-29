-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module Main (main) where
import qualified ByteStringBenchmarks as B
import GHC.Exts (Int(I#))
main :: IO ()
main = I# (B.bytestringReadInt 10000#) `seq` pure ()
