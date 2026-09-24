-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash #-}
module THC.SetWorkload (aggregate, setAggregate) where

import Data.Bits ((.&.))
import Data.List (foldl')
import qualified Data.Set as Set
import GHC.Exts (Int(I#), Int#)

-- Ordinary containers code, including duplicate insertions, mixed-sign keys,
-- and both present and absent deletion/membership queries. The bounded key
-- ranges keep large inputs collision-heavy while the input controls the work.
aggregate :: Int -> Int
aggregate input =
  let n = max 0 input
      key i = ((i * 17 + 11) .&. 63) - 32
      otherKey i = ((i * 13 + 7) .&. 63) - 24
      inserted = foldl' (\s i -> Set.insert (key i)
                                  (Set.insert (key (i `quot` 2)) s))
                         Set.empty [0 .. n - 1]
      deleted = foldl' (\s i -> Set.delete (key (3 * i))
                                 (Set.delete (96 + i) s))
                        inserted [0 .. n `quot` 3 - 1]
      other = foldl' (\s i -> Set.insert (otherKey i) s)
                      Set.empty [0 .. n `quot` 2 - 1]
      united = Set.union deleted other
      shared = Set.intersection deleted other
      remaining = Set.difference deleted other
      -- Set.foldl' visits keys in ascending order. Masking keeps the checksum
      -- bounded without making it commutative or depending on Int overflow.
      ordered = Set.foldl' (\acc k -> (acc * 33 + k + 65) .&. 65535) 0
      queried = foldl' (\acc i ->
                          acc + (if Set.member (key i) deleted
                                 then i + 1 else negate (i + 1))
                              + (if Set.member (128 + i) deleted
                                 then 97 else 3))
                         0 [0 .. n - 1]
  in ordered united + 3 * ordered shared + 5 * ordered remaining
       + 7 * Set.size deleted + 11 * Set.size united + 13 * queried

-- Only the embedding boundary uses primitives. The native oracle calls the
-- same ordinary Haskell aggregate function.
setAggregate :: Int# -> Int#
setAggregate n = case aggregate (I# n) of I# result -> result
