-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.ListCoverage
  ( listPipeline, listAppendReverse, listSpineLazy, listTailLazy
  , streamPrefix, streamKnot, sharedListConsumers
  ) where

import Data.Bits ((.&.))
import GHC.Exts (Int(I#), Int#)
import THC.CoverageSupport (neverInt)

-- Sizes are bounded; payloads retain the whole input, including Int overflow.
{-# NOINLINE buildValues #-}
buildValues :: Int -> Int -> [Int]
buildValues count seed
  | count <= 0 = []
  | otherwise = seed : buildValues (count - 1) (seed + 3)

{-# NOINLINE checksum #-}
checksum :: [Int] -> Int -> Int
checksum values acc = case values of
  [] -> acc
  value : rest -> checksum rest (acc * 33 + value)

-- Observing the spine must never force its bottom-valued heads.
{-# OPAQUE spineLength #-}
spineLength :: [Int] -> Int -> Int
spineLength values acc = case values of
  [] -> acc
  _ : rest -> spineLength rest (acc + 1)

{-# NOINLINE bottomHeads #-}
bottomHeads :: Int -> [Int]
bottomHeads count
  | count <= 0 = []
  | otherwise = neverInt : bottomHeads (count - 1)

{-# OPAQUE neverList #-}
neverList :: [Int]
neverList = neverList

-- Check count before the spine: an exact prefix never demands its tail.
{-# OPAQUE prefixChecksum #-}
prefixChecksum :: Int -> [Int] -> Int -> Int
prefixChecksum count values acc
  | count <= 0 = acc
  | otherwise = case values of
      [] -> acc
      value : rest -> prefixChecksum (count - 1) rest (acc * 17 + value)

{-# NOINLINE fromValues #-}
fromValues :: Int -> [Int]
fromValues seed = seed : fromValues (seed + 1)

-- Preserve one input-dependent lazy spine for separately entered consumers.
{-# OPAQUE sharedProducer #-}
sharedProducer :: Int -> Int -> [Int]
sharedProducer count seed = buildValues count seed

listPipeline :: Int# -> Int#
listPipeline raw = case checksum
  (filter odd (map (+ (n + 1)) (buildValues (n .&. 31) n))) 7 of
    I# answer -> answer
  where n = I# raw

listAppendReverse :: Int# -> Int#
listAppendReverse raw = case checksum
  (reverse (buildValues (n .&. 15) n ++
    buildValues ((n + 3) .&. 7) (n - 11))) 13 of
    I# answer -> answer
  where n = I# raw

listSpineLazy :: Int# -> Int#
listSpineLazy raw = case spineLength (bottomHeads (n .&. 31)) n of I# answer -> answer
  where n = I# raw

listTailLazy :: Int# -> Int#
listTailLazy raw = case prefixChecksum (n .&. 31)
  (buildValues (n .&. 31) n ++ neverList) 5 of I# answer -> answer
  where n = I# raw

streamPrefix :: Int# -> Int#
streamPrefix raw = case prefixChecksum (n .&. 31) (fromValues n) 3 of I# answer -> answer
  where n = I# raw

streamKnot :: Int# -> Int#
streamKnot raw = let n = I# raw; values = n : (n + 1) : values
  in case prefixChecksum (n .&. 31) values 11 of I# answer -> answer

sharedListConsumers :: Int# -> Int#
sharedListConsumers raw = let n = I# raw; spine = sharedProducer (n .&. 31) n
  in case checksum spine 7 + checksum spine 19 of I# answer -> answer
