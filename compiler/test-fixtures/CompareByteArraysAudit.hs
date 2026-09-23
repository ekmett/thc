{-# LANGUAGE MagicHash #-}
module CompareByteArraysAudit where

import GHC.Exts
import Data.Bits ((.&.))
import qualified Data.ByteString.Short as S
import Data.ByteString.Short.Internal (ShortByteString(SBS))

-- Retain genuine public operations across an ordinary call boundary.
{-# OPAQUE ordBytes #-}
ordBytes :: S.ShortByteString -> S.ShortByteString -> Int
ordBytes a b = case compare a b of LT -> -1; EQ -> 0; GT -> 1
{-# OPAQUE prefixBytes #-}
prefixBytes :: S.ShortByteString -> S.ShortByteString -> Int
prefixBytes a b = if S.isPrefixOf a b then 1 else 0
{-# OPAQUE suffixBytes #-}
suffixBytes :: S.ShortByteString -> S.ShortByteString -> Int
suffixBytes a b = if S.isSuffixOf a b then 1 else 0

shortCompare :: Int# -> Int#
shortCompare raw =
  let k = I# raw .&. 4095; x = fromIntegral (I# raw)
      a = S.pack [x,0,128,255]
      b = S.pack (case k `rem` 5 of
        0 -> [x,0,128,255]; 1 -> [x,0,128]; 2 -> [x,0,128,254]
        3 -> [x,0,129,0]; _ -> [])
  in case ordBytes a b of I# n -> n
shortPrefix :: Int# -> Int#
shortPrefix raw =
  let k = I# raw .&. 4095; x = fromIntegral (I# raw)
      a = S.pack [x,0,128]
      b = S.pack (case k `rem` 3 of 0 -> []; 1 -> [x,0,128,255]; _ -> [x,0,129,255])
  in case prefixBytes a b of I# n -> n
shortSuffix :: Int# -> Int#
shortSuffix raw =
  let k = I# raw .&. 4095; x = fromIntegral (I# raw)
      a = S.pack [128,x]
      b = S.pack (case k `rem` 3 of 0 -> []; 1 -> [0,255,128,x]; _ -> [0,255,129,x])
  in case suffixBytes a b of I# n -> n

-- Offsets/count remain dynamic and are always contained. GHC specifies sign,
-- not magnitude, so normalize before observing the native result.
{-# OPAQUE compareRanges #-}
compareRanges :: S.ShortByteString -> Int -> S.ShortByteString -> Int -> Int -> Int
compareRanges (SBS a) (I# from) (SBS b) (I# to) (I# count) =
  case compareByteArrays# a from b to count of
    n -> case n <# 0# of 1# -> -1; _ -> case n ># 0# of 1# -> 1; _ -> 0
rangeCompare :: Int# -> Int#
rangeCompare raw =
  let k = I# raw .&. 4095; x = fromIntegral (I# raw)
      a = S.pack [x,0,127,128,255,17,0,255]
      b = S.pack [255,0,127,128,x,17,255,0]
      from = k `rem` 9; to = (k `quot` 9) `rem` 9
      count = min ((k `quot` 81) `rem` 9) (min (8-from) (8-to))
  in case compareRanges a from b to count of I# n -> n
aliasCompare :: Int# -> Int#
aliasCompare raw =
  let k = I# raw .&. 4095; x = fromIntegral (I# raw)
      a = S.pack [x,0,127,128,255,17,0,255]
      from = k `rem` 9; to = (k `quot` 9) `rem` 9
      count = min ((k `quot` 81) `rem` 9) (min (8-from) (8-to))
  in case compareRanges a from a to count of I# n -> n
