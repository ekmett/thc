-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
module HashableProbe
  ( strictText, strictBytes, shortBytes, lazyText, lazyBytes
  ) where

import Data.Hashable (hashWithSalt)
import qualified Data.ByteString as B
import qualified Data.ByteString.Lazy as BL
import qualified Data.ByteString.Short as BS
import qualified Data.Text as T
import qualified Data.Text.Lazy as TL
import GHC.Exts (Int(..), Int#)

-- All inputs are selected at runtime. The dependency's genuine Hashable
-- instances, not a replacement algorithm or expected-result table, do the work.
lengthFor :: Int -> Int
lengthFor choice = case choice of
  0 -> 0
  1 -> 1
  2 -> 7
  3 -> 16
  4 -> 17
  5 -> 128
  6 -> 129
  7 -> 240
  8 -> 241
  9 -> 1024
  10 -> 4097
  _ -> 79

-- Keep input construction at a separate genuine Core call boundary. The
-- runtime compiler may still choose to inline that call independently of GHC.
{-# NOINLINE bytesFor #-}
bytesFor :: Int -> B.ByteString
bytesFor choice =
  let source = B.pack (take (lengthFor choice + 23) (cycle [0,255,1,127,128,65,240,159,154,128,10]))
  in B.take (lengthFor choice) (B.drop (if choice == 11 then 13 else 0) source)

{-# NOINLINE textFor #-}
textFor :: Int -> T.Text
textFor choice =
  let source = T.pack (take (lengthFor choice + 23) (cycle "a\0\x3bb\x1f680\x4e2d\x301\n"))
  in T.take (lengthFor choice) (T.drop (if choice == 11 then 13 else 0) source)

-- Repeated updates cross the XXH3 short/medium/long boundaries and retain
-- nonzero backing-storage offsets. Empty inputs still reset and digest state.
byteChunks :: B.ByteString -> [B.ByteString]
byteChunks input
  | B.null input = []
  | otherwise = let (front, rest) = B.splitAt 37 input in front : byteChunks rest

textChunks :: T.Text -> [T.Text]
textChunks input
  | T.null input = []
  | otherwise = let (front, rest) = T.splitAt 19 input in front : textChunks rest

{-# NOINLINE strictText #-}
strictText :: Int# -> Int# -> Int#
strictText salt choice = case hashWithSalt (I# salt) (textFor (I# choice)) of I# result -> result

{-# NOINLINE strictBytes #-}
strictBytes :: Int# -> Int# -> Int#
strictBytes salt choice = case hashWithSalt (I# salt) (bytesFor (I# choice)) of I# result -> result

{-# NOINLINE shortBytes #-}
shortBytes :: Int# -> Int# -> Int#
shortBytes salt choice = case hashWithSalt (I# salt) (BS.toShort (bytesFor (I# choice))) of I# result -> result

{-# NOINLINE lazyText #-}
lazyText :: Int# -> Int# -> Int#
lazyText salt choice = case hashWithSalt (I# salt) (TL.fromChunks (textChunks (textFor (I# choice)))) of I# result -> result

{-# NOINLINE lazyBytes #-}
lazyBytes :: Int# -> Int# -> Int#
lazyBytes salt choice = case hashWithSalt (I# salt) (BL.fromChunks (byteChunks (bytesFor (I# choice)))) of I# result -> result
