-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, EmptyCase #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}

-- |
-- Module      : TupleJoinInputAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC 9.14.1 internal library APIs
--
-- Compiler fixture for tuple join input audit Core and metadata.
module TupleJoinInputAudit where
import GHC.Exts
import qualified GHC.Internal.Float as Original

data Box = Box Int#
data Failure = Failure Int#
{-# OPAQUE lazyBottom #-}
lazyBottom :: Box
lazyBottom = raise# (Failure 999#)

{-# OPAQUE forward #-}
forward :: Int# -> Int#
forward x =
  let {-# NOINLINE finish #-}
      finish :: Int# -> (# Int#, Box, Double# #) -> Int#
      finish y (# n, _, d #) = y +# n +# double2Int# d
  in case x <# 0# of
    1# -> finish (x -# 1#) (# x +# 2#, lazyBottom, 3.75## #)
    _ -> finish (x +# 4#) (# x -# 5#, lazyBottom, -6.25## #)

{-# OPAQUE recursiveSwap #-}
recursiveSwap :: Int# -> Int#
recursiveSwap x =
  let {-# NOINLINE go #-}
      go :: Int# -> (# Int#, Int#, Box #) -> Int#
      go n (# a, b, lazy #) = case n <=# 0# of
        1# -> a *# 7# +# b
        _ -> go (n -# 1#) (# b +# 3#, a -# 5#, lazy #)
  in go (word2Int# (int2Word# x `and#` 15##)) (# x, x +# 1#, lazyBottom #)

{-# OPAQUE nested #-}
nested :: Int# -> Int#
nested x =
  let {-# NOINLINE finish #-}
      finish :: (# (# #), (# Int#, Box #), Float# #) -> Int# -> Int#
      finish (# (# #), (# a, Box b #), c #) y = a +# b +# float2Int# c +# y
  in case x <# 0# of
    1# -> finish (# (# #), (# x, Box (x +# 11#) #), 4.5# #) (x -# 13#)
    _ -> finish (# (# #), (# x, Box (x -# 17#) #), -8.5# #) (x +# 19#)

{-# OPAQUE originalRoundTo #-}
originalRoundTo :: Int# -> Int#
originalRoundTo x =
  let digits = case word2Int# (int2Word# x `and#` 7##) of
        0# -> [1,2,5,0]
        1# -> [1,3,5,0]
        2# -> [9,9,9,9]
        3# -> [0,0,0,1]
        4# -> []
        5# -> [5,0,0,0]
        6# -> [4,9,9,9]
        _ -> [1,2,3,4,5]
      precision = I# (word2Int# ((int2Word# x `uncheckedShiftRL#` 3#) `and#` 3##))
      checksum [] = 0#
      checksum (I# a:as) = a +# 11# *# checksum as
  in case Original.roundTo 10 precision digits of
       (I# carry, result) -> carry *# 1000003# +# checksum result

{-# OPAQUE retryAction #-}
retryAction :: State# RealWorld -> (# State# RealWorld, Int #)
retryAction s = case (retry# s :: (# State# RealWorld, Box #)) of {}

{-# OPAQUE emptyRetry #-}
emptyRetry :: Int# -> Int#
emptyRetry x = runRW# (\s -> case atomically#
  (\t -> catchRetry# retryAction (\u -> (# u, I# (x +# 23#) #)) t) s of
    (# _, I# value #) -> value)

{-# OPAQUE raiseAction #-}
raiseAction :: Int# -> State# RealWorld -> (# State# RealWorld, Int #)
raiseAction x s = case (raiseIO# (Failure x) s :: (# State# RealWorld, Box #)) of {}

{-# OPAQUE emptyException #-}
emptyException :: Int# -> Int#
emptyException x = runRW# (\s -> case catch# (raiseAction x)
  (\exception t -> case exception of Failure y -> (# t, I# (y +# 29#) #)) s of
    (# _, I# value #) -> value)
