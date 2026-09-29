-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}

-- |
-- Module      : TypedWorkload
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC primitive tuples, sums and 128-bit vectors
--
-- Ordinary typed calls and captures for code-cache tests.
module TypedWorkload (calculate) where

import GHC.Exts

type Payload = (# (# #), State# RealWorld, Int#, Int16X8# #)
type Choice = (# Int16# | Payload #)

data Fn = Fn (Int# -> Int#)
data Lazy = Lazy Int

{-# OPAQUE passState #-}
passState :: State# RealWorld -> State# RealWorld
passState state = state

{-# OPAQUE passEmpty #-}
passEmpty :: (# #) -> (# #)
passEmpty empty = empty

{-# OPAQUE vector #-}
vector :: Int# -> Int16X8#
vector x = packInt16X8#
  (# intToInt16# x, intToInt16# (x +# 17#), intToInt16# (x +# 34#),
     intToInt16# (x +# 51#), intToInt16# (x +# 68#), intToInt16# (x +# 85#),
     intToInt16# (x +# 102#), intToInt16# (x +# 119#) #)

{-# OPAQUE advance #-}
advance :: Int16X8# -> Int# -> Int16X8#
advance value offset = plusInt16X8# value (broadcastInt16X8# (intToInt16# offset))

{-# OPAQUE checksum #-}
checksum :: Int16X8# -> Int#
checksum value = case unpackInt16X8# value of
  (# a, b, c, d, e, f, g, h #) ->
    int16ToInt# a +# int16ToInt# b *# 2# +# int16ToInt# c *# 3# +#
    int16ToInt# d *# 4# +# int16ToInt# e *# 5# +# int16ToInt# f *# 6# +#
    int16ToInt# g *# 7# +# int16ToInt# h *# 8#

{-# OPAQUE produce #-}
produce :: Int# -> State# RealWorld -> Payload
produce seed state = case passEmpty (# #) of
  empty -> (# empty, passState state, seed, vector seed #)

{-# OPAQUE choose #-}
choose :: Int# -> Payload -> Choice
choose flag payload = case flag of
  0# -> case payload of (# _, _, seed, _ #) -> (# intToInt16# seed | #)
  _ -> (# | payload #)

{-# OPAQUE relay #-}
relay :: Int# -> Choice -> Choice
relay count choice = case count <=# 0# of
  1# -> choice
  _ -> relay (count -# 1#) choice

{-# OPAQUE score #-}
score :: Choice -> Int#
score choice = case choice of
  (# narrow | #) -> int16ToInt# narrow
  (# | (# _, _, seed, value #) #) -> seed +# checksum value

{-# OPAQUE evolve #-}
evolve :: Int# -> Payload -> Choice -> Payload
evolve count initial choice =
  let {-# NOINLINE go #-}
      go :: Int# -> Payload -> Choice -> Payload
      go remaining payload current = case remaining <=# 0# of
        1# -> payload
        _ -> case payload of
          (# empty, state, total, value #) ->
            go (remaining -# 1#)
              (# empty, state, total +# score current, advance value remaining #)
              (choose remaining payload)
  in go count initial choice

{-# OPAQUE consume #-}
consume :: Payload -> Choice -> Int# -> Int#
consume (# _, _, total, value #) choice extra =
  total +# checksum value +# score choice +# extra

{-# OPAQUE makeClosure #-}
makeClosure :: Payload -> Choice -> Int# -> Fn
makeClosure payload choice offset =
  Fn (\extra -> consume payload choice (offset +# extra))

{-# OPAQUE makeThunk #-}
makeThunk :: Payload -> Choice -> Int# -> Lazy
makeThunk payload choice extra = Lazy (I# (consume payload choice extra))

{-# OPAQUE makePap #-}
makePap :: Payload -> Choice -> Fn
makePap payload choice = Fn (consume payload choice)

{-# OPAQUE apply #-}
apply :: (Int# -> Int#) -> Int# -> Int#
apply function value = function value

-- | Compose tuple, sum, vector and unit transport, a typed local loop, and
-- escaping captured closures, a shared thunk and a partial application.
-- The count must be nonnegative; integer and vector lanes wrap at their widths.
{-# OPAQUE calculate #-}
calculate :: Int# -> Int# -> Int# -> Int#
calculate count seed flag = runRW# (\state ->
  case produce seed state of
    initial -> case relay count (choose flag initial) of
      choice -> case evolve count initial choice of
        final -> case makeClosure final choice seed of
          Fn function -> case makePap final choice of
            Fn partial -> case makeThunk final choice (seed +# 7#) of
              Lazy boxed -> case boxed of
                I# shared -> apply function 11# +# apply partial 13# +# shared +# shared)
