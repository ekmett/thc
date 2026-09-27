-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums, NoImplicitPrelude #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}

-- |
-- Module      : SumJoinAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for sum join audit Core and metadata.
module SumJoinAudit where
import GHC.Exts (Int#, Double#, (+#), (-#), (*#), (<=#), int2Double#, double2Int#, runRW#, touch#)

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

-- GHC explicitly permits outer join jumps inside runRW# continuations. Its
-- late beta reduction must not become an ordinary capturing closure in THC.
{-# OPAQUE stateForward #-}
stateForward :: Int# -> (# Box | Int# #)
stateForward x =
  let {-# NOINLINE finish #-}
      finish n = case n <=# 0# of
        1# -> (# bottomBox | #)
        _ -> (# | n +# 29# #)
  in case x <=# 0# of
       1# -> finish (x -# 1#)
       _ -> runRW# (\s -> case touch# bottomBox s of _ -> finish (x +# 1#))

{-# OPAQUE stateRecursive #-}
stateRecursive :: Int# -> (# Int# | Double# #)
stateRecursive x =
  let {-# NOINLINE go #-}
      go n a = case n <=# 0# of
        1# -> case a <=# 0# of
          1# -> (# a | #)
          _ -> (# | int2Double# a #)
        _ -> runRW# (\s -> case touch# bottomBox s of _ -> go (n -# 1#) (a +# 3#))
  in go x (0# -# x)

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
{-# OPAQUE stateForwardCase #-}
stateForwardCase :: Int# -> Int#
stateForwardCase x = case stateForward x of
  (# _ | #) -> -31#
  (# | n #) -> n *# 7#
{-# OPAQUE stateRecursiveCase #-}
stateRecursiveCase :: Int# -> Int#
stateRecursiveCase x = case stateRecursive x of
  (# n | #) -> n -# 37#
  (# | d #) -> double2Int# d +# 41#

-- A sum alternative can emit several stores into its caller's result slots.
-- Keep this boundary opaque so the case cannot dissolve into the scalar observer.
{-# OPAQUE tupleForward #-}
tupleForward :: Int# -> (# Int#, Int#, Int# #)
tupleForward x = case forward x of
  (# _ | #) -> (# -11#, x, x -# 1# #)
  (# | n #) -> (# n, x, n +# 1# #)

{-# OPAQUE tupleForwardCase #-}
tupleForwardCase :: Int# -> Int#
tupleForwardCase x = case tupleForward x of
  (# a, b, c #) -> a +# b *# 2# +# c *# 3#

-- Three physical sum slots include a reference and an inactive scalar. The
-- left payload must remain lazy while the right arm clears the reference slot.
{-# OPAQUE sumForward #-}
sumForward :: Int# -> (# Box | Int# #)
sumForward x = case forward x of
  (# box | #) -> (# box | #)
  (# | n #) -> (# | n +# 2# #)

{-# OPAQUE sumForwardCase #-}
sumForwardCase :: Int# -> Int#
sumForwardCase x = case sumForward x of
  (# _ | #) -> -43#
  (# | n #) -> n *# 11#
