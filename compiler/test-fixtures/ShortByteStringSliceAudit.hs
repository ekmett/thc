-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE BangPatterns, MagicHash #-}
module ShortByteStringSliceAudit where
import GHC.Exts (Int(I#), Int#)
import Data.Word (Word8)
import qualified Data.ByteString.Short as S

-- An ordinary lazy list supplies dynamic bytes, including 0 and 255. The
-- library owns packing, slicing and unpacking; these helpers only observe it.
{-# OPAQUE packed #-}
packed :: Int -> S.ShortByteString
packed seed = S.pack (go 0)
  where
    n = abs (seed `rem` 17)
    go i | i == n = []
         | otherwise = fromIntegral (seed + i*73) : go (i+1)

observe :: S.ShortByteString -> Int# -> Int#
observe s selector = case I# selector of
  -2 -> case checksum 0 (S.unpack s) of I# answer -> answer
  -1 -> case S.length s of I# answer -> answer
  index -> case at index (S.unpack s) of I# answer -> answer
  where
    checksum !a [] = a
    checksum !a (w:ws) = checksum (a*33 + fromIntegral w) ws
    at n _ | n < 0 = -1
    at _ [] = -1
    at 0 (w:_) = fromIntegral w
    at n (_:ws) = at (n-1) ws

{-# OPAQUE takeCase #-}
takeCase :: Int# -> Int# -> Int# -> Int# -> Int#
takeCase seed count _ selector = observe (S.take (I# count) (packed (I# seed))) selector
{-# OPAQUE dropCase #-}
dropCase :: Int# -> Int# -> Int# -> Int# -> Int#
dropCase seed count _ selector = observe (S.drop (I# count) (packed (I# seed))) selector
{-# OPAQUE splitCase #-}
splitCase :: Int# -> Int# -> Int# -> Int# -> Int#
splitCase seed count side selector =
  case S.splitAt (I# count) (packed (I# seed)) of
    (left,right) -> observe (if I# side == 0 then left else right) selector

-- Keep neighboring complete cold overflow paths visible as strict frontiers.
{-# OPAQUE appendFrontier #-}
appendFrontier :: Int# -> Int# -> Int#
appendFrontier x y = observe (S.append (packed (I# x)) (packed (I# y))) (-2#)
{-# OPAQUE concatFrontier #-}
concatFrontier :: Int# -> Int# -> Int#
concatFrontier x y = observe (S.concat [packed (I# x), S.empty, packed (I# y)]) (-2#)
