-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums, NoImplicitPrelude #-}
{-# OPTIONS_GHC -fno-do-lambda-eta-expansion #-}

-- | A lazy chunk stream with typed heap fields for the selected native cache.
-- The count is nonnegative. Each load owns its cells, shared cycle and thunks.
module THC.CachedHeap (calculate, partial) where

import GHC.Exts hiding (build)

type Counters = (# Int16#, Double#, (# #) #)
type Payload = (# Int64X2# | FloatX4# | (# #) #)
data Blocks = End | Block Counters Payload Blocks Blocks

-- | Preserve a constructor-function call and its typed partial application.
{-# OPAQUE partial #-}
partial :: (Counters -> Payload -> Blocks -> Blocks -> Blocks)
        -> Counters -> Payload -> (Blocks -> Blocks -> Blocks)
partial make counters payload = make counters payload

{-# OPAQUE complete #-}
complete :: (Blocks -> Blocks -> Blocks) -> Blocks -> Blocks -> Blocks
complete make rest unused = make rest unused

{-# OPAQUE never #-}
never :: Blocks
never = never

{-# OPAQUE payload #-}
payload :: Int# -> Int# -> Payload
payload selector bits = case andI# selector 3# of
  0# -> (# packInt64X2# (# intToInt64# bits, intToInt64# (bits +# 1#) #) | | #)
  1# -> (# | broadcastFloatX4# (int2Float# (andI# bits 15#)) | #)
  _ -> (# | | (# #) #)

{-# OPAQUE build #-}
build :: Int# -> Int# -> Int# -> Blocks
build count seed selector = case count of
  0# -> End
  _ -> complete (partial Block (# intToInt16# seed, int2Double# count, (# #) #)
                       (payload selector seed))
         (build (count -# 1#) (seed +# 3#) (selector +# 1#)) never

{-# OPAQUE shared #-}
shared :: Blocks
shared = let ring = complete
               (partial Block (# intToInt16# 3#, 2.0##, (# #) #) (# | | (# #) #))
               ring never
         in ring

{-# OPAQUE payloadValue #-}
payloadValue :: Payload -> Int#
payloadValue value = case value of
  (# vector | | #) -> case unpackInt64X2# vector of
    (# first, second #) -> int64ToInt# first +# int64ToInt# second
  (# | vector | #) -> case unpackFloatX4# vector of
    (# first, second, third, fourth #) ->
      float2Int# (plusFloat# (plusFloat# first second) (plusFloat# third fourth))
  (# | | (# #) #) -> 7#

{-# OPAQUE foldBlocks #-}
foldBlocks :: Int# -> Blocks -> Int# -> Int#
foldBlocks count blocks total = case count of
  0# -> total
  _ -> case blocks of
    End -> total
    Block (# narrow, weight, (# #) #) value rest _ ->
      foldBlocks (count -# 1#) rest
        (total +# int16ToInt# narrow +# double2Int# weight +# payloadValue value)

-- | Fold dynamic chunks and a finite prefix of a shared cyclic stream.
-- The unused neighbour diverges, so no constructor/PAP may force it.
{-# OPAQUE calculate #-}
calculate :: Int# -> Int# -> Int# -> Int#
calculate count seed selector =
  foldBlocks count (build count seed selector) seed +# foldBlocks 2# shared 0#
