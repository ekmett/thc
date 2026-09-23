{-# LANGUAGE MagicHash, NoImplicitPrelude, RankNTypes, ScopedTypeVariables, TypeApplications, TypeFamilies, AllowAmbiguousTypes, UnboxedTuples #-}
{-# OPTIONS_GHC -fno-worker-wrapper -fno-specialise -fno-spec-constr -fno-do-lambda-eta-expansion #-}
-- Compiler-only representation evidence, including types THC does not execute.
module RepresentationAudit where
import GHC.Exts (Int#, Addr#, (+#), (-#), (<=#))
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
      done :: forall a. a -> Int#
      done _ = consume n
  in case box of End -> done @Box box; Box _ _ -> done @(Int# -> Int#) consume
data Strict = Strict !Box
{-# OPAQUE strictField #-}
strictField :: Strict -> Int#
strictField (Strict b) = firstField b
{-# OPAQUE functionJoin #-}
functionJoin :: Int# -> Box -> Int# -> Int#
functionJoin n box =
  let {-# NOINLINE doneFunction #-}
      doneFunction :: forall a. a -> Int# -> Int#
      doneFunction _ x = n +# x
  in case box of End -> doneFunction @Box box; Box _ _ -> doneFunction @(Int# -> Int#) consume

{-# OPAQUE polyJoinEntry #-}
polyJoinEntry :: Int# -> Int#
polyJoinEntry n = polyJoin n (case n <=# 0# of 1# -> End; _ -> Box n End)
{-# OPAQUE functionJoinEntry #-}
functionJoinEntry :: Int# -> Int#
functionJoinEntry n = functionJoin n (case n <=# 0# of 1# -> End; _ -> Box n End) n
