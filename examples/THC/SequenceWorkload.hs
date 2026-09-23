{-# LANGUAGE MagicHash #-}
module THC.SequenceWorkload
  ( sequenceBuild, sequenceEnds, sequenceAppend, sequenceSplit
  , sequenceIndexUpdate, sequenceAggregate, sequenceLazyPayloads
  , sequenceBuildViews, sequenceDequeViews, sequenceAppendViews, sequenceLazyLength
  ) where

import Data.Bits ((.&.))
import qualified Data.Foldable as Foldable
import qualified Data.Sequence as Seq
import GHC.Exts (Int(I#), Int#)

boundedSize :: Int -> Int
boundedSize = max 0 . min 1024

payload :: Int -> Int
payload i = ((37 * i + 11) .&. 2047) - 1024

fromList :: Int -> Seq.Seq Int
fromList n = Seq.fromList [payload i | i <- [0 .. n - 1]]

-- An independent public-API slice for end insertion: it does not stand in for
-- fromList coverage, which is retained separately and in the full workload.
fromEnds :: Int -> Seq.Seq Int
fromEnds n = Foldable.foldl' step Seq.empty [0 .. n - 1]
  where
    step xs i
      | i .&. 1 == 0 = payload i Seq.<| xs
      | otherwise = xs Seq.|> payload i

measure :: Seq.Seq Int -> Int
measure xs = forward + 3 * backward + 7 * Seq.length xs
  where
    forward = Foldable.foldl' (\acc x -> (acc * 33 + (x .&. 65535) + 1) .&. 2147483647) 0 xs
    backward = Foldable.foldr (\x acc -> (acc * 17 + (x .&. 65535) + 1) .&. 2147483647) 0 xs

drain :: Bool -> Seq.Seq Int -> Int
drain takeLeft = go takeLeft 0
  where
    go left checksum xs
      | left = case Seq.viewl xs of
          Seq.EmptyL -> checksum
          x Seq.:< rest -> go False ((checksum * 19 + (x .&. 65535) + 1) .&. 2147483647) rest
      | otherwise = case Seq.viewr xs of
          Seq.EmptyR -> checksum
          rest Seq.:> x -> go True ((checksum * 19 + (x .&. 65535) + 1) .&. 2147483647) rest

splitScore :: Seq.Seq Int -> Int
splitScore xs = Foldable.foldl' score 0 [-2, 0, 1, count `quot` 2, count - 1, count, count + 2]
  where
    count = Seq.length xs
    score acc position = case Seq.splitAt position xs of
      (left, right) -> acc * 5 + measure left + 11 * measure right

indexUpdateScore :: Seq.Seq Int -> Int
indexUpdateScore xs = measure updated + 13 * queries + 17 * indexed
  where
    count = Seq.length xs
    updated = Seq.update (-1) 99999 (Seq.update count 88888
                (Seq.update (count `quot` 2) (payload (count + 17)) xs))
    queries = Foldable.foldl' (\acc position -> acc * 7 +
                case Seq.lookup position updated of Nothing -> -31; Just value -> value)
              0 [-1, 0, count `quot` 2, count - 1, count, count + 1]
    indexed = if Seq.null updated then -47
              else Seq.index updated (count `quot` 2)

sequenceBuild :: Int# -> Int#
sequenceBuild raw = case measure (fromList (boundedSize (I# raw))) of I# result -> result

sequenceEnds :: Int# -> Int#
sequenceEnds raw = case result of I# value -> value
  where
    xs = fromEnds (boundedSize (I# raw))
    result = measure xs + 5 * drain True xs + 11 * drain False xs

sequenceAppend :: Int# -> Int#
sequenceAppend raw = case measure (left Seq.>< right) + 3 * measure (right Seq.>< left) of I# result -> result
  where
    n = boundedSize (I# raw)
    left = fromEnds n
    right = fromEnds (n `quot` 2)

sequenceSplit :: Int# -> Int#
sequenceSplit raw = case splitScore (fromEnds (boundedSize (I# raw))) of I# result -> result

sequenceIndexUpdate :: Int# -> Int#
sequenceIndexUpdate raw = case indexUpdateScore (fromEnds (boundedSize (I# raw))) of I# result -> result

sequenceAggregate :: Int# -> Int#
sequenceAggregate raw = case result of I# value -> value
  where
    n = boundedSize (I# raw)
    initial = fromList n
    edged = if n == 0 then initial else payload (n + 1) Seq.<| (initial Seq.|> payload (n + 2))
    combined = edged Seq.>< fromList (n `quot` 2)
    result = measure initial + 3 * measure combined + 5 * drain True combined
               + 7 * drain False combined + 11 * splitScore combined
               + 13 * indexUpdateScore combined

-- Sequence structure is strict, but lifted element payloads must stay lazy.
-- The two self-referential payloads are never demanded by length/spine folds.
sequenceLazyPayloads :: Int# -> Int#
sequenceLazyPayloads raw = case result of I# value -> value
  where
    hidden = hidden :: Int
    xs = hidden Seq.<| (fromEnds (boundedSize (I# raw)) Seq.|> hidden)
    result = Seq.length xs + Foldable.foldl' (\acc _ -> acc + 1) 0 xs

-- These are separately reported API slices, not replacements for the folds,
-- splitting or indexing above. Their audits can establish a smaller frontier.
viewMeasure :: Seq.Seq Int -> Int
viewMeasure xs = drain True xs + 3 * drain False xs + 7 * Seq.length xs

sequenceBuildViews :: Int# -> Int#
sequenceBuildViews raw = case viewMeasure (fromList (boundedSize (I# raw))) of I# result -> result

sequenceDequeViews :: Int# -> Int#
sequenceDequeViews raw = case viewMeasure (fromEnds (boundedSize (I# raw))) of I# result -> result

sequenceAppendViews :: Int# -> Int#
sequenceAppendViews raw = case viewMeasure (left Seq.>< right) + 3 * viewMeasure (right Seq.>< left) of I# result -> result
  where
    n = boundedSize (I# raw)
    left = fromEnds n
    right = fromEnds (n `quot` 2)

sequenceLazyLength :: Int# -> Int#
sequenceLazyLength raw = case result of I# value -> value
  where
    hidden = hidden :: Int
    xs = hidden Seq.<| (fromEnds (boundedSize (I# raw)) Seq.|> hidden)
    result = Seq.length xs + if Seq.null xs then 10000 else 1
