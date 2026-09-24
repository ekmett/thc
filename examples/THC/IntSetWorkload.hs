-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.IntSetWorkload (aggregate, intSetAggregate) where

import Data.Bits ((.&.))
import Data.List (foldl')
import qualified Data.IntSet as IntSet
import GHC.Exts (Int(I#), Int#)

-- Mixed-sign Patricia prefixes and dense 64-bit bitmap leaves, with duplicate
-- insertion, absent deletion, set algebra and signed ascending traversal.
aggregate :: Int -> Int
aggregate input =
  let n = max 0 input
      key i = ((37 * i + 11) .&. 1023) - 512
      otherKey i = ((53 * i + 7) .&. 2047) - 1024
      seeded = if n == 0 then IntSet.empty else
        foldl' (flip IntSet.insert) IntSet.empty [minBound, maxBound, -65, -64, -1, 0, 63, 64]
      inserted = foldl' (\s i -> IntSet.insert (key i) (IntSet.insert (key (i `quot` 3)) s))
                        seeded [0 .. n - 1]
      deleted = foldl' (\s i -> IntSet.delete (key (3 * i)) (IntSet.delete (4096 + i) s))
                       inserted [0 .. n `quot` 4 - 1]
      otherSeed = if n == 0 then IntSet.empty else
        IntSet.singleton (if n .&. 1 == 0 then minBound else maxBound)
      other = foldl' (\s i -> IntSet.insert (otherKey i) s) otherSeed [0 .. n `quot` 2 - 1]
      united = IntSet.union deleted other
      shared = IntSet.intersection deleted other
      remaining = IntSet.difference deleted other
      ordered s = foldl' (\acc k -> (acc * 33 + (k .&. 65535) + 1) .&. 2147483647)
                         0 (IntSet.toAscList s)
      queried = foldl' (\acc i ->
                         let k = ((97 * i + 13) .&. 2047) - 1024
                         in acc + (if IntSet.member k deleted then i + 1 else negate (i + 1)))
                       0 [0 .. n - 1]
      edges = foldl' (\acc k -> acc * 3 + if IntSet.member k remaining then 1 else 0)
                     0 [minBound, -65, -64, -1, 0, 63, 64, maxBound]
  in ordered united + 3 * ordered shared + 5 * ordered remaining + 7 * queried
       + 11 * IntSet.size deleted + 13 * IntSet.size united
       + 17 * IntSet.size shared + 19 * IntSet.size remaining + 23 * edges

intSetAggregate :: Int# -> Int#
intSetAggregate n = case aggregate (I# n) of I# result -> result
