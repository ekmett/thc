-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude, RankNTypes, ScopedTypeVariables, TypeApplications, TypeFamilies, AllowAmbiguousTypes, UnboxedTuples #-}
-- Keep the polymorphic entry boundaries visible instead of specializing them.
-- Keep closed recursive joins local instead of floating them into functions.
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr -fno-do-lambda-eta-expansion #-}

-- |
-- Module      : RepresentationAudit
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC-specific primitive types and operations
--
-- Pre-Tidy representation/source evidence, consumed by source-core tests.
-- Boxed joins also exercise erased type slots, strict entry obligations and
-- returned-function suffixes and scalar swaps across an empty-tuple join input
-- through both runtime backends; no post-Tidy claim.
-- Inputs: this source via the existing source-core exporter. Output: the existing
-- build/source-core/RepresentationAudit.cbd, consumed by RealCoreJoinTest.
module RepresentationAudit where
import GHC.Exts (Int#, Addr#, (+#), (-#), (<=#), int2Word#, word2Int#, and#)
data Box = Box Int# Box | End
newtype Wrapped = Wrapped (Int# -> Int#)
type family Family a
{-# OPAQUE familyIdentity #-}
familyIdentity :: Family a -> Family a
familyIdentity x = x
{-# OPAQUE wrappedIdentity #-}
wrappedIdentity :: Wrapped -> Wrapped
wrappedIdentity x = x
{-# OPAQUE applyLong #-}
applyLong :: (Int# -> Int#) -> Int# -> Int#
applyLong f x = f x
{-# OPAQUE addressIdentity #-}
addressIdentity :: Addr# -> Addr#
addressIdentity x = x
{-# OPAQUE emptyTuple #-}
emptyTuple :: (# #) -> (# #)
emptyTuple x = x
{-# OPAQUE firstField #-}
firstField :: Box -> Int#
firstField b = case b of
  End -> 0#
  Box n rest -> case rest of End -> n; Box m _ -> n +# m
{-# OPAQUE consume #-}
consume :: Int# -> Int#
consume n = n +# 7#
{-# OPAQUE joinLoop #-}
joinLoop :: Int# -> Int#
joinLoop n = let go k acc = case k <=# 0# of
                             1# -> acc
                             _ -> go (k -# 1#) (acc +# (n -# k +# 1#))
             in go n 0#
{-# OPAQUE polyJoin #-}
polyJoin :: Int# -> Box -> Int#
polyJoin n box =
  let {-# NOINLINE done #-}
      -- Type arguments erase, but the used boxed value retains its entry slot.
      done :: forall a. a -> Box -> Int#
      done _ value = case value of End -> consume n; Box k _ -> consume k
  in case box of End -> done @Box box box; Box _ _ -> done @(Int# -> Int#) consume box
data Strict = Strict !Box
{-# OPAQUE strictField #-}
strictField :: Strict -> Int#
strictField (Strict b) = firstField b
{-# OPAQUE functionJoin #-}
functionJoin :: Int# -> Box -> Box -> Int#
functionJoin n box =
  let {-# NOINLINE doneFunction #-}
      -- The used boxed suffix belongs to the returned function, not the join.
      doneFunction :: forall a. a -> Box -> Int#
      doneFunction _ value = case value of End -> n; Box x _ -> n +# x
  in case box of End -> doneFunction @Box box; Box _ _ -> doneFunction @(Int# -> Int#) consume

{-# OPAQUE polyJoinEntry #-}
polyJoinEntry :: Int# -> Int#
polyJoinEntry n = polyJoin n (case n <=# 0# of 1# -> End; _ -> Box n End)
{-# OPAQUE functionJoinEntry #-}
functionJoinEntry :: Int# -> Int#
functionJoinEntry n = functionJoin n (case n <=# 0# of 1# -> End; _ -> Box n End) (Box n End)

-- | Swap 11 and 29 across an empty-tuple join parameter, then subtract.
-- The low five input bits bound recursion; odd depths return 18, even -18.
{-# OPAQUE emptyTupleSwap #-}
emptyTupleSwap :: Int# -> Int#
emptyTupleSwap x = emptyTupleSwapDepth (word2Int# (and# (int2Word# x) 31##))

{-# OPAQUE emptyTupleSwapDepth #-}
emptyTupleSwapDepth :: Int# -> Int#
emptyTupleSwapDepth depth =
  let {-# NOINLINE go #-}
      go a u n b = case n <=# 0# of
        1# -> case u of (# #) -> a -# b
        _ -> go b u (n -# 1#) a
  in go 11# (# #) depth 29#
