-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, NoImplicitPrelude #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}
module TupleJoinAudit where
import GHC.Exts (Int#, (+#), (-#), (*#), (<=#), and#, int2Word#, word2Int#)

data Box = Box Int#
{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

{-# OPAQUE forward #-}
forward :: Int# -> (# Int#, Box #)
forward x =
  let {-# NOINLINE finish #-}
      finish n = (# n +# 7#, bottomBox #)
  in case x <=# 0# of
       1# -> finish (x -# 31#)
       _ -> finish (x +# 41#)

{-# OPAQUE recursive #-}
recursive :: Int# -> Int# -> (# Int#, Box #)
recursive depth x =
  let {-# NOINLINE go #-}
      go n a = case n <=# 0# of
        1# -> (# a, bottomBox #)
        _ -> go (n -# 1#) (a +# 3#)
  in go depth x

{-# OPAQUE mutual #-}
mutual :: Int# -> Int# -> (# Int#, Box #)
mutual depth x =
  let {-# NOINLINE even #-}
      even n a = case n <=# 0# of
        1# -> (# a +# 11#, bottomBox #)
        _ -> odd (n -# 1#) (a +# 2#)
      {-# NOINLINE odd #-}
      odd n a = case n <=# 0# of
        1# -> (# a +# 13#, bottomBox #)
        _ -> even (n -# 1#) (a +# 5#)
  in even depth x

{-# OPAQUE nestedForward #-}
nestedForward :: Int# -> (# Int#, Box #)
nestedForward x =
  let {-# NOINLINE finish #-}
      finish n = (# n +# 17#, bottomBox #)
  in let {-# NOINLINE inner #-}
         inner n = case n <=# 0# of
           1# -> finish (n -# 5#)
           _ -> finish (n +# 9#)
     in inner x

{-# OPAQUE empty #-}
empty :: Int# -> (# #)
empty x =
  let {-# NOINLINE done #-}
      done n = case n <=# 0# of 1# -> (# #); _ -> (# #)
  in done x

{-# OPAQUE nested #-}
nested :: Int# -> (# (# Int#, (# #) #), (# Box #) #)
nested x =
  let {-# NOINLINE done #-}
      done n = (# (# n +# 19#, (# #) #), (# bottomBox #) #)
  in done x

{-# OPAQUE forwardCase #-}
forwardCase :: Int# -> Int#
forwardCase x = case forward x of (# n, _ #) -> n *# 3#
{-# OPAQUE recursiveCase #-}
recursiveCase :: Int# -> Int#
recursiveCase x = case recursive (word2Int# (and# (int2Word# x) 31##)) x of (# n, _ #) -> n +# 23#
{-# OPAQUE mutualCase #-}
mutualCase :: Int# -> Int#
mutualCase x = case mutual (word2Int# (and# (int2Word# x) 31##)) x of (# n, _ #) -> n *# 5#
{-# OPAQUE nestedForwardCase #-}
nestedForwardCase :: Int# -> Int#
nestedForwardCase x = case nestedForward x of (# n, _ #) -> n -# 29#
{-# OPAQUE emptyCase #-}
emptyCase :: Int# -> Int#
emptyCase x = case empty x of (# #) -> x +# 37#
{-# OPAQUE nestedCase #-}
nestedCase :: Int# -> Int#
nestedCase x = case nested x of (# (# n, (# #) #), (# _ #) #) -> n +# 13#
{-# OPAQUE recursiveDepth #-}
recursiveDepth :: Int# -> Int#
recursiveDepth x = case recursive x 5# of (# n, _ #) -> n
{-# OPAQUE mutualDepth #-}
mutualDepth :: Int# -> Int#
mutualDepth x = case mutual x 5# of (# n, _ #) -> n
