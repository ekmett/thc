-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Native GHC observer for the aggregate heap fields fixture.
module Main where

import AggregateHeapFields
import GHC.Exts (Int(I#), Int#)

entries :: [(String, Int# -> Int#)]
entries = [("boxedTag", boxedTag), ("mixedTuple", mixedTuple),
  ("nestedTuple", nestedTuple), ("emptyTuple", emptyTuple),
  ("sumTuple", sumTuple), ("sumIgnoreLazy", sumIgnoreLazy),
  ("sharedLazy", sharedLazy), ("floatingEdges", floatingEdges),
  ("originalBoxedTag", originalBoxedTag)]

-- Thirteen integer boundaries; their low bits exercise all eight IEEE cases.
inputs :: [Int]
inputs = [minBound, -2147483649, -2147483648, -5, -1, 0, 1, 2, 3, 4, 5, 6, maxBound]

main :: IO ()
main = sequence_
  [putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (entry value)))
  | (name, entry) <- entries, x@(I# value) <- inputs]
