{-# LANGUAGE MagicHash, NoImplicitPrelude, GADTs, UnliftedNewtypes #-}
{-# OPTIONS_GHC -fno-cpr-anal #-}
-- A worker retains an equality coercion before the strict boxed tree slot.
module CbvCoercionAudit where
import GHC.Exts (Int(I#), Int#, (+#))

data Spine = Done | More Int# Spine
data Witness a where
  Witness :: (a ~ Int) => Int# -> Witness a

{-# NOINLINE witnessed #-}
witnessed :: Witness a -> Spine -> a
witnessed (Witness n) tree = case tree of
  Done -> I# n
  More k _ -> I# (n +# k)

{-# OPAQUE coercionEntry #-}
coercionEntry :: Int# -> Int#
coercionEntry n = case witnessed (Witness n) (More 7# Done) of I# result -> result

-- Legal erased scalar casts preserve IntRep across the newtype boundary.
newtype RawInt = RawInt Int#

{-# OPAQUE wrapRaw #-}
wrapRaw :: Int# -> RawInt
wrapRaw x = RawInt x

{-# OPAQUE unwrapRaw #-}
unwrapRaw :: RawInt -> Int#
unwrapRaw (RawInt x) = x

{-# OPAQUE scalarCastEntry #-}
scalarCastEntry :: Int# -> Int#
scalarCastEntry x = unwrapRaw (wrapRaw x)
