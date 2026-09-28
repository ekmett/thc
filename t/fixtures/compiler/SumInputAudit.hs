-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}

-- |
-- Module      : SumInputAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Compiler fixture for sum input audit Core and metadata.
module SumInputAudit where
import GHC.Exts

data Box = Box Int#
data Failure = Failure Int#
data Fn = Fn (Int# -> Int#)
{-# OPAQUE lazyBottom #-}
lazyBottom :: Box
lazyBottom = raise# (Failure 999#)

type Mixed = (# (# Int#, Box, Float# #) | (# Double#, Box #) #)

{-# OPAQUE produce #-}
produce :: Int# -> Mixed
produce x = case x <# 0# of
  1# -> (# (# x, lazyBottom, 3.75# #) | #)
  _ -> (# | (# -7.25##, Box (x +# 11#) #) #)

{-# OPAQUE consume #-}
consume :: Mixed -> Int# -> Int# -> Int#
consume s y z = case s of
  (# (# x, _, f #) | #) -> x +# float2Int# f +# y *# 3# +# z
  (# | (# d, Box x #) #) -> double2Int# d +# x +# y *# 5# -# z

{-# OPAQUE apply2 #-}
apply2 :: (Int# -> Int# -> Int#) -> Int# -> Int# -> Int#
apply2 f x y = f x y

{-# OPAQUE apply1 #-}
apply1 :: (Int# -> Int#) -> Int# -> Int#
apply1 f x = f x

{-# OPAQUE direct #-}
direct :: Int# -> Int#
direct x = case produce x of s -> consume s (x -# 3#) (x +# 5#)

{-# OPAQUE pap #-}
pap :: Int# -> Int#
pap x = case produce x of s -> apply2 (consume s) (x -# 7#) (x +# 13#)

{-# OPAQUE capture #-}
capture :: Int# -> Int#
capture x = case produce x of
  s -> let {-# NOINLINE saved #-}
           saved y = consume s y (x +# 17#)
       in apply1 saved (x -# 19#) +# apply1 saved (x +# 23#)

{-# OPAQUE loop #-}
loop :: Mixed -> Int# -> Int# -> Int#
loop s n a = case n <=# 0# of
  1# -> consume s a 31#
  _ -> loop s (n -# 1#) (a +# 2#)

{-# OPAQUE tailInput #-}
tailInput :: Int# -> Int#
tailInput x = case produce x of s -> loop s (word2Int# (int2Word# x `and#` 15##)) x

{-# OPAQUE over #-}
over :: Mixed -> Int# -> (Int# -> Int#)
over s x = case makeFn s x of Fn f -> f

{-# OPAQUE makeFn #-}
makeFn :: Mixed -> Int# -> Fn
makeFn s x = Fn (consume s (x +# 29#))

{-# OPAQUE overapply #-}
overapply :: Int# -> Int#
overapply x = case produce x of s -> over s x (x -# 31#)

{-# OPAQUE emptyConsume #-}
emptyConsume :: (# (# #) | Int# #) -> Int#
emptyConsume s = case s of (# (# #) | #) -> 37#; (# | x #) -> x +# 41#

{-# OPAQUE emptyPayload #-}
emptyPayload :: Int# -> Int#
emptyPayload x = case x <# 0# of
  1# -> emptyConsume (# (# #) | #)
  _ -> emptyConsume (# | x #)

{-# OPAQUE refConsume #-}
refConsume :: (# Box | Int# #) -> Int#
refConsume s = case s of (# Box x | #) -> x +# 43#; (# | x #) -> x -# 47#

{-# OPAQUE referenceSlots #-}
referenceSlots :: Int# -> Int#
referenceSlots x = refConsume (# Box (x +# 53#) | #) +# refConsume (# | x -# 59# #)

{-# OPAQUE prefixBox #-}
prefixBox :: Int# -> Fn
prefixBox x = case produce x of s -> Fn (consume s 61#)

{-# OPAQUE captureBox #-}
captureBox :: Int# -> Fn
captureBox x = case produce x of s -> Fn (\y -> consume s y x)

{-# OPAQUE escapedPap #-}
escapedPap :: Int# -> Int#
escapedPap x = case prefixBox x of
  Fn f -> case prefixBox (negateInt# x) of
    Fn g -> apply1 f 67# +# apply1 g 71# +# apply1 f 73#

{-# OPAQUE escapedCapture #-}
escapedCapture :: Int# -> Int#
escapedCapture x = case captureBox x of
  Fn f -> case captureBox (negateInt# x) of
    Fn g -> apply1 f 79# +# apply1 g 83# +# apply1 f 89#
