{-# LANGUAGE MagicHash #-}
-- Ordinary Data.Int conversion and storage, with Int# only at the host entry.
module THC.NarrowIntCoverage (narrowRecordChecksum) where

import Data.Int (Int8, Int16, Int32)
import GHC.Exts (Int(I#), Int#)

-- Unpacking makes the retained worker fields Int8Rep, Int16Rep and Int32Rep.
-- The opaque producer and consumer keep the width-changing conversions and
-- narrow fields in optimized Core instead of allowing the record to disappear.
data Sample = Sample {-# UNPACK #-} !Int8
                     {-# UNPACK #-} !Int16
                     {-# UNPACK #-} !Int32

{-# OPAQUE packSample #-}
packSample :: Int -> Sample
packSample value = Sample (fromIntegral value) (fromIntegral value) (fromIntegral value)

{-# OPAQUE scoreSample #-}
scoreSample :: Sample -> Int
scoreSample (Sample byte short word) =
  fromIntegral byte * 3 + fromIntegral short * 5 + fromIntegral word * 7

-- Decode eight records around an arbitrary signed machine integer. The
-- recurrence wraps as Int, while every record stores signed narrow fields.
-- The input controls data only, so even minBound/maxBound terminate promptly.
narrowRecordChecksum :: Int# -> Int#
narrowRecordChecksum raw = case go 8 (I# raw) 0 of I# result -> result
  where
    go :: Int -> Int -> Int -> Int
    go remaining value checksum
      | remaining <= 0 = checksum
      | otherwise = go (remaining - 1) (value * 257 + 129)
                       (checksum * 17 + scoreSample (packSample value))
