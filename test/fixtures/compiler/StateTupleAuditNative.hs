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
-- Native GHC observer for the state tuple audit fixture.
module Main where
import GHC.Exts
import StateTupleAudit
main :: IO ()
main = mapM_ run [(name, f, a) | (name, f) <- entries, a <- inputs]
  where
    entries = [("pairCase", pairCase), ("lazyCase", lazyCase), ("captureCase", captureCase)]
    inputs = [minBound, -4097, -1, 0, 1, 4097, maxBound] :: [Int]
    run (name, f, a@(I# x)) = putStrLn (name ++ "\t" ++ show a ++ "\t" ++ show (I# (f x)))
