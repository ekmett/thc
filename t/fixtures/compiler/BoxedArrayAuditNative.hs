-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}

-- |
-- Module      : Main
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : MagicHash
--
-- Native observations of public lifted arrays, lazy reads, snapshots and capture.
module Main (main) where

import GHC.Exts (Int(I#), Int#)
import qualified BoxedArrayAudit as P

entries :: [(String, Int# -> Int#)]
entries = [("boxedSTRecursive", P.boxedSTRecursive), ("boxedZero", P.boxedZero),
           ("boxedSnapshot", P.boxedSnapshot), ("boxedClosure", P.boxedClosure)]

inputs :: [Int]
inputs = [minBound, minBound + 1, -(2^32), -1, 0, 1, 2^32, maxBound - 1, maxBound]

row :: String -> (Int# -> Int#) -> Int -> IO ()
row name f x@(I# raw) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f raw)))

-- | Emit the consumed native observations without stdin or a generated request
-- file. Signed extremes exercise machine-width arithmetic independently of THC.
main :: IO ()
main = mapM_ (\(name, f) -> mapM_ (row name f) inputs) entries
