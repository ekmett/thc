{-# LANGUAGE MagicHash #-}
module THC.IntMapWorkload (aggregate, intMapAggregate) where

import Data.Bits ((.&.))
import Data.List (foldl')
import qualified Data.IntMap.Strict as IntMap
import GHC.Exts (Int(I#), Int#)

-- Mixed-sign Patricia-tree branches, duplicate histogram keys, adjustments,
-- collapsing deletions, and ascending signed traversal of boundary keys.
aggregate :: Int -> Int
aggregate input =
  let n = max 0 input
      key i = ((i * 37 + 11) .&. 127) - 64
      seeded = if n == 0 then IntMap.empty else
        IntMap.insert minBound 3 (IntMap.insert (-1) 5
          (IntMap.insert 0 7 (IntMap.singleton maxBound 11)))
      histogram = foldl' (\m i -> IntMap.insertWith (+) (key i) ((i .&. 7) + 1) m)
                         seeded [0 .. n - 1]
      adjusted = foldl' (\m i -> IntMap.adjust (+ 7) (key (3 * i)) m)
                        histogram [0 .. n `quot` 4 - 1]
      boundaryDeleted = IntMap.delete (if n .&. 1 == 0 then minBound else maxBound) adjusted
      deleted = foldl' (\m i -> IntMap.delete (key (5 * i)) (IntMap.delete (2048 + i) m))
                       boundaryDeleted [0 .. n `quot` 5 - 1]
      queried = foldl' (\acc i ->
                         let k = ((i * 29 + 7) .&. 255) - 128
                         in acc + IntMap.findWithDefault (-13) k deleted
                                + (if IntMap.member k deleted then i + 1 else negate (i + 1)))
                       0 [0 .. n - 1]
      edges = foldl' (\acc k -> acc + IntMap.findWithDefault (-19) k deleted)
                     0 [minBound, -1, 0, maxBound]
      ordered = IntMap.foldlWithKey'
        (\acc k v -> (acc * 33 + (k .&. 65535) + 3 * v) .&. 2147483647) 0 deleted
  in ordered + 17 * queried + 23 * edges + 31 * IntMap.size deleted

intMapAggregate :: Int# -> Int#
intMapAggregate n = case aggregate (I# n) of I# result -> result
