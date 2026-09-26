-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}
module SumJoinInputAudit where
import GHC.Exts

data Box = Box Int#
data Failure = Failure Int#
data Fn = Fn (Int# -> Int#)
type Mixed = (# (# Int#, Box, Float# #) | (# Double#, Box #) #)
{-# OPAQUE lazyBottom #-}
lazyBottom :: Box
lazyBottom = raise# (Failure 999#)
{-# OPAQUE produce #-}
produce :: Int# -> Mixed
produce x = case x <# 0# of
  1# -> (# (# x, lazyBottom, 3.75# #) | #)
  _ -> (# | (# -7.25##, Box (x +# 11#) #) #)
{-# OPAQUE consume #-}
consume :: Mixed -> Int# -> Int#
consume s y = case s of
  (# (# x, _, f #) | #) -> x +# float2Int# f +# y *# 3#
  (# | (# d, Box x #) #) -> double2Int# d +# x +# y *# 5#

{-# OPAQUE forward #-}
forward :: Int# -> Int#
forward x =
  let {-# NOINLINE finish #-}
      finish s y = consume s (y +# 7#)
  in case x <# 0# of
       1# -> finish (produce x) (x -# 11#)
       _ -> finish (produce (negateInt# x)) (x +# 13#)

{-# OPAQUE recursiveSwap #-}
recursiveSwap :: Int# -> Int#
recursiveSwap x = case produce x of
  first -> case produce (negateInt# x -# 1#) of
    second ->
      let {-# NOINLINE go #-}
          go n a b = case n <=# 0# of
            1# -> consume a 17# -# consume b 19#
            _ -> go (n -# 1#) b a
      in go (word2Int# (int2Word# x `and#` 31##)) first second

{-# OPAQUE mutual #-}
mutual :: Int# -> Int#
mutual x = case produce x of
  initial ->
    let {-# NOINLINE even #-}
        even n s y = case n <=# 0# of
          1# -> consume s y
          _ -> odd (n -# 1#) s (y +# 23#)
        {-# NOINLINE odd #-}
        odd n s y = case n <=# 0# of
          1# -> consume s (y -# 29#)
          _ -> even (n -# 1#) s (y -# 31#)
    in even (word2Int# (int2Word# x `and#` 15##)) initial x

{-# OPAQUE captured #-}
captured :: Int# -> Int#
captured x = case produce x of
  s -> let {-# NOINLINE finish #-}
           finish y = consume s (y +# 37#)
       in case x <# 0# of
            1# -> finish (x -# 41#)
            _ -> finish (x +# 43#)

{-# OPAQUE captureResult #-}
captureResult :: Int# -> Fn
captureResult x = case produce x of
  s -> let {-# NOINLINE finish #-}
           finish y = Fn (\z -> consume s (y +# z))
       in case x <# 0# of
            1# -> finish (x -# 47#)
            _ -> finish (x +# 53#)
{-# OPAQUE apply #-}
apply :: (Int# -> Int#) -> Int# -> Int#
apply f x = f x
{-# OPAQUE escapedCapture #-}
escapedCapture :: Int# -> Int#
escapedCapture x = case captureResult x of Fn f -> apply f 59# +# apply f 61#

{-# OPAQUE emptyPayload #-}
emptyPayload :: Int# -> Int#
emptyPayload x =
  let {-# NOINLINE finish #-}
      finish s = case s of (# (# #) | #) -> 67#; (# | y #) -> y +# 71#
  in case x <# 0# of
       1# -> finish (# (# #) | #)
       _ -> finish (# | x #)

{-# OPAQUE changingTag #-}
changingTag :: Int# -> Int#
changingTag x =
  let {-# NOINLINE go #-}
      go n s = case n <=# 0# of
        1# -> case s of (# _ | #) -> 73#; (# | y #) -> y +# 79#
        _ -> case s of
          (# _ | #) -> go (n -# 1#) (# | x #)
          (# | _ #) -> go (n -# 1#) (# lazyBottom | #)
  in go (word2Int# (int2Word# x `and#` 31##)) (# lazyBottom | #)
