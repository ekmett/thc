{-# LANGUAGE MagicHash #-}
module THC.NarrowWordCoverage
  ( word8Arithmetic, word16Arithmetic, word32Arithmetic, narrowWordRecordChecksum
  ) where

import Data.List (foldl')
import Data.Word (Word8, Word16, Word32)
import GHC.Exts (Int(I#), Int#)

word8Arithmetic :: Int# -> Int#
word8Arithmetic raw = case score (I# raw) of I# result -> result
  where
    score input =
      let a = fromIntegral input :: Word8
          b = fromIntegral (input `quot` 257 + 11) :: Word8
          wrapped = (a + 17) * (b + 3) - 29
          flags = (if a < b then 1 else 0) + (if a == b then 2 else 0)
                    + (if wrapped <= a then 4 else 0)
      in fromIntegral wrapped + 257 * flags

word16Arithmetic :: Int# -> Int#
word16Arithmetic raw = case score (I# raw) of I# result -> result
  where
    score input =
      let a = fromIntegral input :: Word16
          b = fromIntegral (input `quot` 257 + 11) :: Word16
          wrapped = (a + 17) * (b + 3) - 29
          flags = (if a < b then 1 else 0) + (if a == b then 2 else 0)
                    + (if wrapped <= a then 4 else 0)
      in fromIntegral wrapped + 65537 * flags

word32Arithmetic :: Int# -> Int#
word32Arithmetic raw = case score (I# raw) of I# result -> result
  where
    score input =
      let a = fromIntegral input :: Word32
          b = fromIntegral (input `quot` 257 + 11) :: Word32
          wrapped = (a + 17) * (b + 3) - 29
          flags = (if a < b then 1 else 0) + (if a == b then 2 else 0)
                    + (if wrapped <= a then 4 else 0)
      in fromIntegral wrapped + 4294967297 * flags

data Sample = Sample {-# UNPACK #-} !Word8
                     {-# UNPACK #-} !Word16
                     {-# UNPACK #-} !Word32

-- Two ordinary consumers share the records, retaining the unpacked unsigned
-- fields in optimized Core without an optimizer fence on either consumer.
narrowWordRecordChecksum :: Int# -> Int#
narrowWordRecordChecksum raw = case checksum (I# raw) of I# result -> result
  where
    build :: Int -> Int -> [Sample]
    build remaining value
      | remaining <= 0 = []
      | otherwise = Sample (fromIntegral value) (fromIntegral (value + 129))
                           (fromIntegral (value * 65537 + 1))
                      : build (remaining - 1) (value * 257 + 129)
    checksum input =
      let records = build 8 input
          forward = foldl' (\acc (Sample byte short word) ->
                      acc * 17 + 3 * fromIntegral byte + 5 * fromIntegral short
                        + 7 * fromIntegral word) 0 records
          backward = foldr (\(Sample byte short word) acc ->
                       acc * 33 + fromIntegral byte + 11 * fromIntegral short
                         + 13 * fromIntegral word) 0 records
      in forward + backward
