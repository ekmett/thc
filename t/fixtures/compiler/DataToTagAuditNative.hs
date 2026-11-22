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
-- Native observations of saturated constructor-to-tag families.
module Main (main) where

import Data.List (sort)
import GHC.Exts (Int(I#), Int#)
import qualified DataToTagAudit as P

entries :: [(String, Int# -> Int#)]
entries = [("smallCase", P.smallCase), ("largeCase", P.largeCase), ("lazyCase", P.lazyCase),
           ("pairCase", P.pairCase), ("unliftedCase", P.unliftedCase), ("forceCase", P.forceCase),
           ("wrappedCase", P.wrappedCase)]

inputs :: [Int]
inputs = sort ([-18 .. 18] ++ [minBound, minBound + 1, -(2^32), -4097, 4097, 2^32, maxBound - 1, maxBound])

-- | A negative input makes forceCase demand bottom; that exception is a Java
-- control, not a native observation.
domain :: String -> [Int]
domain "forceCase" = filter (>= 0) inputs
domain _ = inputs

row :: String -> (Int# -> Int#) -> Int -> IO ()
row name f x@(I# raw) = putStrLn (name ++ "\t" ++ show x ++ "\t" ++ show (I# (f raw)))

-- | Emit the consumed native observations without stdin or a generated request
-- file. Signed extremes exercise machine-width tag arithmetic independently of THC.
main :: IO ()
main = mapM_ (\(name, f) -> mapM_ (row name f) (domain name)) entries
