-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, NoImplicitPrelude, GADTs, UnliftedNewtypes #-}
{-# LANGUAGE ExplicitNamespaces, StandaloneKindSignatures, UnliftedDatatypes #-}
{-# OPTIONS_GHC -fno-cpr-anal #-}
-- A worker retains an equality coercion before the strict boxed tree slot.
module CBVCoercionAudit where
import GHC.Exts (Int(I#), Int#, Word#, UnliftedType, (+#), int2Word#)
import Data.Type.Equality (type (~))

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

-- These casts surround primitive applications themselves. Signature validation
-- must see their unchanged primitive representations after newtype erasure.
newtype RawWord = RawWord Word#

{-# OPAQUE addRaw #-}
addRaw :: RawInt -> RawInt -> RawInt
addRaw (RawInt x) (RawInt y) = RawInt (x +# y)

{-# OPAQUE rawToWord #-}
rawToWord :: RawInt -> RawWord
rawToWord (RawInt x) = RawWord (int2Word# x)

-- Newtype casts change the boxed class proof, never its levity. The ignored
-- lifted field stays lazy even inside an unlifted boxed product.
newtype WrappedSpine = WrappedSpine Spine
type Product :: UnliftedType
data Product = Product Int# Spine
newtype WrappedProduct = WrappedProduct Product

{-# OPAQUE wrapSpine #-}
wrapSpine :: Spine -> WrappedSpine
wrapSpine x = WrappedSpine x

{-# OPAQUE unwrapSpine #-}
unwrapSpine :: WrappedSpine -> Spine
unwrapSpine (WrappedSpine x) = x

{-# OPAQUE wrapProduct #-}
wrapProduct :: Product -> WrappedProduct
wrapProduct x = WrappedProduct x

{-# OPAQUE unwrapProduct #-}
unwrapProduct :: WrappedProduct -> Product
unwrapProduct (WrappedProduct x) = x

{-# OPAQUE bottomSpine #-}
bottomSpine :: Spine
bottomSpine = bottomSpine

{-# OPAQUE boxedCastEntry #-}
boxedCastEntry :: Int# -> Int#
boxedCastEntry x = case unwrapSpine (wrapSpine (More x bottomSpine)) of
  More n _ -> n
  Done -> 0#

{-# OPAQUE unliftedBoxedCastEntry #-}
unliftedBoxedCastEntry :: Int# -> Int#
unliftedBoxedCastEntry x = case unwrapProduct (wrapProduct (Product x bottomSpine)) of
  Product n _ -> n
