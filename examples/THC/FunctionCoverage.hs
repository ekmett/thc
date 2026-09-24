-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.FunctionCoverage
  ( closurePipeline, capturedOverapply, reusedLazyPAP, sharedCapturedThunk, chooseBinary ) where

import Data.Bits ((.&.))
import GHC.Exts (Int(I#), Int#)
import THC.CoverageSupport

data Binary = Binary (Int -> Int -> Int)
data Unary = Unary (Int -> Int)

-- A list transports closures with different primitive captures and behavior.
{-# NOINLINE makeFunctions #-}
makeFunctions :: Int -> Int -> [Int -> Int]
makeFunctions count seed
  | count <= 0 = []
  | even seed = (\x -> x + seed) : makeFunctions (count - 1) (seed + 3)
  | otherwise = (\x -> x * 3 + seed) : makeFunctions (count - 1) (seed + 3)

{-# OPAQUE runFunctions #-}
runFunctions :: [Int -> Int] -> Int -> Int
runFunctions functions value = case functions of
  [] -> value
  f : rest -> runFunctions rest (f value)

{-# OPAQUE makeBinary #-}
makeBinary :: Int -> Binary
makeBinary seed
  | even seed = Binary (\x y -> seed + x * 7 + y)
  | otherwise = Binary (\x y -> seed + x + y * 11)

-- Exporting this helper preserves its independently callable arity-one entry;
-- without that boundary, -O2 eta-expands it to its sole saturated caller.
-- The optimized-Core audit must still confirm the three-argument overcall.
{-# OPAQUE chooseBinary #-}
chooseBinary :: Int -> (Int -> Int -> Int)
chooseBinary seed = case makeBinary seed of Binary f -> f

{-# OPAQUE reuseUnary #-}
reuseUnary :: (Int -> Int) -> Int -> Int -> Int
reuseUnary f first second = f first * 5 + f second

{-# OPAQUE retainUnary #-}
retainUnary :: (Int -> Int) -> Unary
retainUnary f = Unary f

closurePipeline :: Int# -> Int#
closurePipeline raw = case runFunctions (makeFunctions (n .&. 7) n) (n - 5) of
  I# answer -> answer
  where n = I# raw

capturedOverapply :: Int# -> Int#
capturedOverapply raw = case chooseBinary n (n + 2) (n - 3) of I# answer -> answer
  where n = I# raw

reusedLazyPAP :: Int# -> Int#
reusedLazyPAP raw = let partial = combine3 (I# raw) neverInt
  in case reuseUnary partial 3 7 of I# answer -> answer

sharedCapturedThunk :: Int# -> Int#
sharedCapturedThunk raw = let n = I# raw; sharedValue = expensiveInt n
  in case retainUnary (\argument -> combine3 sharedValue neverInt argument) of
    Unary f -> case reuseUnary f (n + 1) (n - 1) of I# answer -> answer
