-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Compile the same scalar and constructor modules with ordinary GHC. Produce native.tsv
-- with inputs, cross-module arithmetic/byte reads, recursion, boxed-case results and IEEE float/double bits.
module Main (main) where

import GHC.Exts (Int(I#))
import NativeHiScalar (entry, recursive)
import qualified NativeHiBox as Box
import System.Environment (getArgs)

main :: IO ()
main = do
  [output] <- getArgs
  writeFile output $ unlines
    [ unwords (map show [x, I# (entry raw), I# (recursive raw), I# (Box.entry raw), I# (Box.floatBits raw), I# (Box.doubleBits raw)])
    | x@(I# raw) <- [-3, 0, 2, 7, 31]
    ]
