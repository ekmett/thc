-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Compile the same two scalar modules with ordinary GHC. Produce native.tsv
-- with inputs, cross-module arithmetic and recursive results for runtime tests.
module Main (main) where

import GHC.Exts (Int(I#))
import NativeHiScalar (entry, recursive)
import System.Environment (getArgs)

main :: IO ()
main = do
  [output] <- getArgs
  writeFile output $ unlines
    [ unwords (map show [x, I# (entry raw), I# (recursive raw)])
    | x@(I# raw) <- [-3, 0, 1, 7, 31]
    ]
