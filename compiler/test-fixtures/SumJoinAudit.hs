-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums, NoImplicitPrelude #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}
module SumJoinAudit where
import GHC.Exts (Int#, Double#, (+#), (-#), (*#), (<=#), int2Double#, double2Int#)

data Box = Box Int#
{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

-- Mixed reference/scalar projections exercise clearing the inactive arm. The
-- boxed payload is deliberately never forced by any consumer.
{-# OPAQUE forward #-}
forward :: Int# -> (# Box | Int# #)
forward x =
  let {-# NOINLINE finish #-}
      finish n = case n <=# 0# of
        1# -> (# bottomBox | #)
        _ -> (# | n +# 17# #)
  in case x <=# 0# of
       1# -> finish (x -# 1#)
       _ -> finish (x +# 1#)

{-# OPAQUE recursive #-}
recursive :: Int# -> (# Int# | Double# #)
recursive x =
  let {-# NOINLINE go #-}
      go n a = case n <=# 0# of
        1# -> case a <=# 0# of
          1# -> (# a | #)
          _ -> (# | int2Double# a #)
        _ -> go (n -# 1#) (a +# 3#)
  in go x (0# -# x)

{-# OPAQUE nested #-}
nested :: Int# -> (# (# Int#, Box #) | (# #) #)
nested x =
  let {-# NOINLINE finish #-}
      finish :: Int# -> (# (# Int#, Box #) | (# #) #)
      finish n = (# (# n +# 7#, bottomBox #) | #)
  in let {-# NOINLINE inner #-}
         inner n = case n <=# 0# of
           1# -> (# | (# #) #)
           _ -> finish n
     in inner x

{-# OPAQUE forwardCase #-}
forwardCase :: Int# -> Int#
forwardCase x = case forward x of
  (# _ | #) -> -11#
  (# | n #) -> n *# 3#
{-# OPAQUE recursiveCase #-}
recursiveCase :: Int# -> Int#
recursiveCase x = case recursive x of
  (# n | #) -> n -# 13#
  (# | d #) -> double2Int# d +# 19#
{-# OPAQUE nestedCase #-}
nestedCase :: Int# -> Int#
nestedCase x = case nested x of
  (# (# n, _ #) | #) -> n *# 5#
  (# | (# #) #) -> -23#
