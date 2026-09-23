{-# LANGUAGE AllowAmbiguousTypes, DataKinds, GADTs, KindSignatures, MagicHash, ScopedTypeVariables, TypeApplications, UnboxedTuples #-}
module UnsafeEqualityAudit where
import GHC.Exts
import Data.Kind (Type)
import Unsafe.Coerce (UnsafeEquality(..), unsafeEqualityProof, unsafeCoerce)

data Payload (a :: Type) = Payload Int# a
data Failure = Failure

{-# OPAQUE primitive #-}
primitive :: forall (a :: TYPE IntRep) (b :: TYPE IntRep). a -> b
primitive x = case unsafeEqualityProof @a @b of UnsafeRefl -> x

{-# OPAQUE lifted #-}
lifted :: Payload Int -> Payload Bool
lifted = unsafeCoerce

{-# OPAQUE tuple #-}
tuple :: forall a b. Int# -> Payload a -> (# Int#, Payload b #)
tuple x value = case unsafeEqualityProof @a @b of UnsafeRefl -> (# x +# 3#, value #)

{-# OPAQUE lazyValue #-}
lazyValue :: forall a b. Payload a -> Payload b
lazyValue value = case unsafeEqualityProof @a @b of UnsafeRefl -> value

{-# OPAQUE primitiveCase #-}
primitiveCase :: Int# -> Int#
primitiveCase x = primitive @Int# @Int# x +# 5#

{-# OPAQUE liftedCase #-}
liftedCase :: Int# -> Int#
liftedCase x = case lifted (Payload x (raise# Failure)) of Payload y _ -> y +# 11#

{-# OPAQUE tupleCase #-}
tupleCase :: Int# -> Int#
tupleCase x = case tuple @Int @Bool x (Payload x (raise# Failure)) of (# a, Payload b _ #) -> a +# b

{-# OPAQUE lazyCase #-}
lazyCase :: Int# -> Int#
lazyCase x = case lazyValue @Int @Bool (Payload x (raise# Failure)) of Payload y _ -> y -# 13#

{-# OPAQUE unusedCase #-}
unusedCase :: Int# -> Int#
unusedCase x = case x <# 0# of
  1# -> primitive @Int# @Int# x +# 7#
  _ -> x +# 17#

{-# OPAQUE lazyBottom #-}
lazyBottom :: Int# -> Payload Bool
lazyBottom _ = lazyValue @Int @Bool (raise# Failure)

{-# OPAQUE keep #-}
keep :: a -> Int# -> Int#
keep _ x = x

{-# OPAQUE unusedBottomCase #-}
unusedBottomCase :: Int# -> Int#
unusedBottomCase x = keep (lazyBottom x) (x +# 19#)

-- These do not satisfy GHC's case predicate. Their real proof dependency must
-- remain missing unless an actual source body is supplied separately.
{-# OPAQUE firstClassProof #-}
firstClassProof :: Int# -> UnsafeEquality Int Bool
firstClassProof _ = unsafeEqualityProof

{-# OPAQUE retainProof #-}
retainProof :: forall a b. UnsafeEquality a b -> UnsafeEquality a b
retainProof x = x

{-# OPAQUE liveBinder #-}
liveBinder :: forall a b. Int# -> UnsafeEquality a b
liveBinder _ = case unsafeEqualityProof @a @b of value@UnsafeRefl -> retainProof value

-- A local proof producer must be forced; its name is not the wired GHC Id.
{-# OPAQUE wrongCallee #-}
wrongCallee :: forall a b. Int# -> UnsafeEquality a b
wrongCallee _ = raise# Failure

{-# OPAQUE wrongCalleeCase #-}
wrongCalleeCase :: Int# -> Int#
wrongCalleeCase x = case wrongCallee @Int @Int x of UnsafeRefl -> x

{-# OPAQUE demandedBottomCase #-}
demandedBottomCase :: Int# -> Int#
demandedBottomCase x = case lazyBottom x of Payload y _ -> y

{-# OPAQUE nested #-}
nested :: forall (a :: TYPE IntRep) (b :: TYPE IntRep) (c :: TYPE IntRep). a -> c
nested x = case unsafeEqualityProof @a @b of
  UnsafeRefl -> case unsafeEqualityProof @b @c of UnsafeRefl -> x

{-# OPAQUE nestedCase #-}
nestedCase :: Int# -> Int#
nestedCase x = nested @Int# @Int# @Int# x -# 23#
