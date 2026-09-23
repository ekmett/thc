{-# LANGUAGE DataKinds, ExplicitForAll, KindSignatures, MagicHash #-}
{-# LANGUAGE NoImplicitPrelude, PolyKinds, TypeFamilies, UnboxedSums, UnboxedTuples, UnliftedNewtypes #-}
-- Metadata-only coverage: these declarations are checked by native GHC, but
-- their unboxed boundaries are deliberately rejected by the THC runtime.
module AggregateLayoutAudit where
import GHC.Exts (Int#, Word#, Float#, Double#, State#, RealWorld, RuntimeRep(..), Levity, TYPE, raise#)

data Box = Box Int#
newtype Wrapped = Wrapped Box
newtype FunctionWrapped = FunctionWrapped (Int# -> Int#)
type family Family a

type Nested s = (# (# #), State# s, (# Int# #), (# Int# | (# Box, (# Word#, State# s #) #) #) #)

{-# OPAQUE nestedIdentity #-}
nestedIdentity :: Nested s -> Nested s
nestedIdentity x = x

{-# OPAQUE lazyIdentity #-}
lazyIdentity :: (# Box, Int# -> Int#, a, Wrapped, FunctionWrapped, Family a #)
             -> (# Box, Int# -> Int#, a, Wrapped, FunctionWrapped, Family a #)
lazyIdentity x = x

{-# OPAQUE alternativesIdentity #-}
alternativesIdentity :: (# (# #) | State# s | (# Int# #) | (# Float#, Double#, Int# #) | (# Int# | Box #) #)
                     -> (# (# #) | State# s | (# Int# #) | (# Float#, Double#, Int# #) | (# Int# | Box #) #)
alternativesIdentity x = x

{-# OPAQUE polymorphicTuple #-}
polymorphicTuple :: forall (r :: RuntimeRep) (a :: TYPE r). Box -> (# a, Int# #)
polymorphicTuple x = raise# x

{-# OPAQUE polymorphicSum #-}
polymorphicSum :: forall (r :: RuntimeRep) (a :: TYPE r). Box -> (# a | Int# #)
polymorphicSum x = raise# x

-- GHC's representation view must see through these constructor-free aliases.
-- A newtype over a scalar still must not acquire a boxed data/closure proof.
newtype TupleAlias = TupleAlias (# (# #), Int# #)
newtype EmptyAlias = EmptyAlias (# #)
newtype SumAlias = SumAlias (# State# RealWorld | Box #)
newtype NestedAlias = NestedAlias TupleAlias

{-# OPAQUE tupleAliasIdentity #-}
tupleAliasIdentity :: TupleAlias -> TupleAlias
tupleAliasIdentity x = x

{-# OPAQUE sumAliasIdentity #-}
sumAliasIdentity :: SumAlias -> SumAlias
sumAliasIdentity x = x

{-# OPAQUE nestedAliasIdentity #-}
nestedAliasIdentity :: NestedAlias -> NestedAlias
nestedAliasIdentity x = x

{-# OPAQUE emptyAliasIdentity #-}
emptyAliasIdentity :: EmptyAlias -> EmptyAlias
emptyAliasIdentity x = x

{-# OPAQUE polymorphicNested #-}
polymorphicNested :: forall (r :: RuntimeRep) (a :: TYPE r). Box -> (# (# a, State# RealWorld #), (# a | Int# #) #)
polymorphicNested x = raise# x

-- Knowing that an abstract type has a TupleRep kind is not a logical tuple
-- decomposition. It may itself be a newtype/type variable; do not invent one.
{-# OPAQUE abstractTupleRep #-}
abstractTupleRep :: forall (r :: RuntimeRep) (a :: TYPE ('TupleRep '[r, 'IntRep])). Box -> a
abstractTupleRep x = raise# x

{-# OPAQUE levityPolymorphic #-}
levityPolymorphic :: forall (l :: Levity) (a :: TYPE ('BoxedRep l)). Box -> (# a, Int# #)
levityPolymorphic x = raise# x

newtype Recursive = Recursive Recursive
{-# OPAQUE recursiveNewtypeIdentity #-}
recursiveNewtypeIdentity :: Recursive -> Recursive
recursiveNewtypeIdentity x = x
