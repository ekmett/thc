{-# LANGUAGE MagicHash, NoImplicitPrelude, RankNTypes, ScopedTypeVariables, TypeApplications #-}
{-# OPTIONS_GHC -fno-worker-wrapper -fno-specialise -fno-spec-constr -fno-do-lambda-eta-expansion -fno-full-laziness #-}
-- Keep the raw type/value join prefix visible so the ABI alignment is audited.
module CbvJoinAudit where
import GHC.Exts (Int#, (+#), (<=#))
data Spine = Done | More Int# Spine

{-# OPAQUE consume #-}
consume :: Int# -> Int#
consume n = n +# 7#

{-# OPAQUE polyJoin #-}
polyJoin :: Int# -> Spine -> Int#
polyJoin n tree =
  let {-# NOINLINE done #-}
      done :: forall a. a -> Spine -> Int#
      done _ t = case t of Done -> consume n; More k _ -> consume k
  in case tree of
    Done -> done @Spine tree tree
    More _ _ -> done @(Int# -> Int#) consume tree

{-# OPAQUE functionJoin #-}
functionJoin :: Int# -> Spine -> Spine -> Int#
functionJoin n tree =
  let {-# NOINLINE doneFunction #-}
      doneFunction :: forall a. a -> Spine -> Int#
      doneFunction _ t = case t of Done -> consume n; More k _ -> consume k
  in case tree of
    Done -> doneFunction @Spine tree
    More _ _ -> doneFunction @(Int# -> Int#) consume

{-# OPAQUE joinEntry #-}
joinEntry :: Int# -> Int#
joinEntry n = polyJoin n (case n <=# 0# of 1# -> Done; _ -> More n Done)
{-# OPAQUE functionJoinEntry #-}
functionJoinEntry :: Int# -> Int#
functionJoinEntry n = functionJoin n Done (More n Done)
