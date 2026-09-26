-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
module AggregateHeapFields where

import GHC.Exts
import qualified GHC.Core.TyCon as Compiler

-- This is the same source shape as GHC's PrimRep.BoxedRep. Its worker has
-- one aggregate field: (# (# #) | LevityLike #), not a boxed Maybe field.
data LevityLike = LiftedLike | UnliftedLike
data Boxed = Boxed {-# UNPACK #-} !(Maybe LevityLike)
data Mixed = Mixed Int# (# Int#, Int, Double# #) Int#
data Nested = Nested Int# (# (# Int#, Int #), Double# #) Int#
data Empty = Empty Int# (# #) Int#
data Sum = Sum Int# (# (# #) | (# Int#, Int, Double# #) #) Int#
data Shared = Shared Int# (# Int, Int #) Int#

data Failure = Failure
lazyBottom :: Int
lazyBottom = raise# Failure

{-# OPAQUE makeBoxed #-}
makeBoxed :: Int# -> Boxed
makeBoxed x = case x <# 0# of
  1# -> Boxed Nothing
  _ -> case x ==# 0# of
    1# -> Boxed (Just LiftedLike)
    _ -> Boxed (Just UnliftedLike)

{-# OPAQUE boxedTag #-}
boxedTag :: Int# -> Int#
boxedTag x = case makeBoxed x of
  Boxed Nothing -> 11#
  Boxed (Just LiftedLike) -> 23#
  Boxed (Just UnliftedLike) -> 37#

{-# OPAQUE makeMixed #-}
makeMixed :: Int# -> Mixed
makeMixed x = Mixed (x -# 1#) (# x, lazyBottom, 2.5## #) (x +# 3#)

{-# OPAQUE mixedTuple #-}
mixedTuple :: Int# -> Int#
mixedTuple x = case makeMixed x of
  Mixed before (# value, _, fraction #) after ->
    before +# value +# double2Int# (fraction *## 4.0##) +# after

{-# OPAQUE delayed #-}
delayed :: Int# -> Int
delayed x = I# (x +# 7#)

{-# OPAQUE makeNested #-}
makeNested :: Int# -> Nested
makeNested x = Nested (x -# 5#) (# (# x, delayed x #), -3.25## #) (x +# 9#)

{-# OPAQUE nestedTuple #-}
nestedTuple :: Int# -> Int#
nestedTuple x = case makeNested x of
  Nested before (# (# value, I# lazy #), fraction #) after ->
    before +# value +# lazy +# double2Int# (fraction *## 4.0##) +# after

{-# OPAQUE makeEmpty #-}
makeEmpty :: Int# -> Empty
makeEmpty x = Empty (x -# 13#) (# #) (x +# 17#)

{-# OPAQUE emptyTuple #-}
emptyTuple :: Int# -> Int#
emptyTuple x = case makeEmpty x of
  Empty before (# #) after -> before +# after

{-# OPAQUE makeSum #-}
makeSum :: Int# -> Sum
makeSum x = case x <# 0# of
  1# -> Sum (x -# 19#) (# (# #) | #) (x +# 29#)
  _ -> Sum (x -# 19#) (# | (# x, delayed x, 1.75## #) #) (x +# 29#)

{-# OPAQUE sumTuple #-}
sumTuple :: Int# -> Int#
sumTuple x = case makeSum x of
  Sum before (# (# #) | #) after -> before +# after
  Sum before (# | (# value, I# lazy, fraction #) #) after ->
    before +# value +# lazy +# double2Int# (fraction *## 4.0##) +# after

{-# OPAQUE makeLazySum #-}
makeLazySum :: Int# -> Sum
makeLazySum x = case x <# 0# of
  1# -> Sum (x -# 31#) (# (# #) | #) (x +# 43#)
  _ -> Sum (x -# 31#) (# | (# x, lazyBottom, -2.75## #) #) (x +# 43#)

{-# OPAQUE sumIgnoreLazy #-}
sumIgnoreLazy :: Int# -> Int#
sumIgnoreLazy x = case makeLazySum x of
  Sum before (# (# #) | #) after -> before +# after
  Sum before (# | (# value, _, fraction #) #) after ->
    before +# value +# double2Int# (fraction *## 4.0##) +# after

{-# OPAQUE makeShared #-}
makeShared :: Int# -> Shared
makeShared x = let shared = delayed x
  in Shared (x -# 47#) (# shared, shared #) (x +# 53#)

{-# OPAQUE sharedLazy #-}
sharedLazy :: Int# -> Int#
sharedLazy x = case makeShared x of
  Shared before (# I# first, I# second #) after -> before +# first +# second +# after

-- Keep IEEE edge values inside the heap field. Returning an integer mask
-- avoids depending on host conversions of NaNs, infinities or signed zero.
{-# OPAQUE makeFloating #-}
makeFloating :: Int# -> Mixed
makeFloating x = case andI# x 7# of
  0# -> Mixed 0# (# x, lazyBottom, 0.0## #) 0#
  1# -> Mixed 0# (# x, lazyBottom, negateDouble# 0.0## #) 0#
  2# -> Mixed 0# (# x, lazyBottom, 4.9406564584124654e-324## #) 0#
  3# -> Mixed 0# (# x, lazyBottom, -4.9406564584124654e-324## #) 0#
  4# -> Mixed 0# (# x, lazyBottom, 1.0## /## 0.0## #) 0#
  5# -> Mixed 0# (# x, lazyBottom, (-1.0##) /## 0.0## #) 0#
  6# -> Mixed 0# (# x, lazyBottom, 0.0## /## 0.0## #) 0#
  _ -> Mixed 0# (# x, lazyBottom, -2.5## #) 0#

{-# OPAQUE floatingEdges #-}
floatingEdges :: Int# -> Int#
floatingEdges x = case makeFloating x of
  Mixed _ (# _, _, value #) _ ->
    (value /=## value) +# 2# *# ((1.0## /## value) <## 0.0##)
      +# 4# *# (value ==## 4.9406564584124654e-324##)
      +# 8# *# (value ==## (-4.9406564584124654e-324##))
      +# 16# *# (value ==## 0.0##)

-- Use the real installed compiler declaration too. This bounded probe does
-- not create a GHC session or replace any GHC library implementation.
{-# OPAQUE makeOriginal #-}
makeOriginal :: Int# -> Compiler.PrimRep
makeOriginal x = case x <# 0# of
  1# -> Compiler.BoxedRep Nothing
  _ -> case x ==# 0# of
    1# -> Compiler.BoxedRep (Just Compiler.Lifted)
    _ -> Compiler.BoxedRep (Just Compiler.Unlifted)

{-# OPAQUE originalBoxedTag #-}
originalBoxedTag :: Int# -> Int#
originalBoxedTag x = case makeOriginal x of
  Compiler.BoxedRep Nothing -> 11#
  Compiler.BoxedRep (Just Compiler.Lifted) -> 23#
  Compiler.BoxedRep (Just Compiler.Unlifted) -> 37#
  _ -> -1#
