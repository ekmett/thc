{-# LANGUAGE BangPatterns, MagicHash #-}
module THC.MapWorkload (aggregate, mapAggregate) where

import Data.Bits ((.&.))
import Data.List (foldl')
import qualified Data.Map.Strict as Map
import GHC.Exts (Int(I#), Int#)

-- Ordinary containers code: repeated keys build a histogram, adjustments
-- update existing entries, and queries include both present and absent keys.
-- The input controls the amount of work; there are no OPAQUE/NOINLINE fences.
aggregate :: Int -> Int
aggregate input =
  let n = max 0 input
      key i = (i * 1103515245 + 12345) .&. 4095
      histogram = foldl' (\m i -> Map.insertWith (+) (key i) 1 m)
                         Map.empty [0 .. n - 1]
      adjusted = foldl' (\m i -> Map.adjust (+ 7) (key (3 * i)) m)
                        histogram [0 .. n `quot` 4 - 1]
      queried = foldl' (\acc i -> acc + Map.findWithDefault 0
                         ((i * 48271 + 17) .&. 8191) adjusted)
                       0 [0 .. n - 1]
      weighted = Map.foldlWithKey' (\acc k v -> acc + (k + 1) * v)
                                   0 adjusted
  in weighted + queried + Map.size adjusted

-- Only the embedding boundary mentions primitives; aggregate is ordinary
-- Prelude/containers Haskell and is shared unchanged with the native oracle.
mapAggregate :: Int# -> Int#
mapAggregate n = case aggregate (I# n) of I# result -> result
