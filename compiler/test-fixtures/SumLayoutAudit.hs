{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums, DataKinds, PolyKinds, ExplicitForAll, KindSignatures, UnliftedNewtypes #-}
module SumLayoutAudit where
import GHC.Exts

{-# OPAQUE returnedSum #-}
returnedSum :: Int# -> (# Int# | Word# #)
returnedSum x = case x <# 0# of
  1# -> (# x | #)
  _ -> (# | int2Word# (x +# 1#) #)

sumCase :: Int# -> Int#
sumCase x = case returnedSum x of
  (# a | #) -> a -# 3#
  (# | b #) -> word2Int# b +# 7#

-- GHC may remove direct sums entirely; the export must record what survives.
directCase :: Int# -> Int#
directCase x = case (case x <# 0# of
  1# -> (# x | #)
  _ -> (# | int2Word# (x +# 1#) #)) of
    (# a | #) -> a -# 3#
    (# | b #) -> word2Int# b +# 7#

{-# OPAQUE nestedSum #-}
nestedSum :: Int# -> (# (# Int#, Int# #) | (# Int# | Double# #) #)
nestedSum x = case x <# 0# of
  1# -> (# (# x, x +# 2# #) | #)
  _ -> case x ==# 0# of
    1# -> (# | (# 11# | #) #)
    _ -> (# | (# | 2.5## #) #)

nestedCase :: Int# -> Int#
nestedCase x = case nestedSum x of
  (# (# a,b #) | #) -> a +# b
  (# | (# a | #) #) -> a
  (# | (# | d #) #) -> double2Int# d

{-# OPAQUE lazySum #-}
lazySum :: Int# -> (# (Int,Int) | Float# #)
lazySum x = case x <# 0# of
  1# -> let bottom = bottom in (# (I# x, bottom) | #)
  _ -> (# | 3.5# #)

lazyCase :: Int# -> Int#
lazyCase x = case lazySum x of
  (# (I# a,_) | #) -> a
  (# | f #) -> float2Int# f

{-# OPAQUE zeroSum #-}
zeroSum :: Int# -> State# s -> (# State# s | (# #) #)
zeroSum x s = case x <# 0# of
  1# -> (# s | #)
  _ -> (# | (# #) #)

zeroCase :: Int# -> Int#
zeroCase x = runRW# (\s -> case zeroSum x s of
  (# _ | #) -> 17#
  (# | (# #) #) -> 23#)

{-# OPAQUE unitSum #-}
unitSum :: Int# -> (# () | (# #) #)
unitSum x = case x <# 0# of
  1# -> (# () | #)
  _ -> (# | (# #) #)

unitCase :: Int# -> Int#
unitCase x = case unitSum x of
  (# () | #) -> 31#
  (# | (# #) #) -> 37#

-- Both payloads are boxed references; only the unit payload is lifted.
{-# OPAQUE boxedKindsSum #-}
boxedKindsSum :: Int# -> (# () | ByteArray# #)
boxedKindsSum x = case x <# 0# of
  1# -> (# () | #)
  _ -> runRW# (\s -> case newByteArray# 0# s of
    (# s1,a #) -> case unsafeFreezeByteArray# a s1 of
      (# _,b #) -> (# | b #))

boxedKindsCase :: Int# -> Int#
boxedKindsCase x = case boxedKindsSum x of
  (# () | #) -> 41#
  (# | b #) -> sizeofByteArray# b


{-# OPAQUE floatDoubleSum #-}
floatDoubleSum :: Int# -> (# Float# | Double# #)
floatDoubleSum x = case x <# 0# of
  1# -> (# 3.5# | #)
  _ -> (# | 2.5## #)

floatDoubleCase :: Int# -> Int#
floatDoubleCase x = case floatDoubleSum x of
  (# f | #) -> float2Int# f
  (# | d #) -> double2Int# d

{-# OPAQUE narrowWideSum #-}
narrowWideSum :: Int# -> (# Int32# | Word64# #)
narrowWideSum x = case x <# 0# of
  1# -> (# intToInt32# x | #)
  _ -> (# | wordToWord64# (int2Word# x) #)

narrowWideCase :: Int# -> Int#
narrowWideCase x = case narrowWideSum x of
  (# a | #) -> int32ToInt# a
  (# | b #) -> word2Int# (word64ToWord# b)

{-# OPAQUE threeWaySum #-}
threeWaySum :: Int# -> (# (# #) | Int# | Word# #)
threeWaySum x = case x <# 0# of
  1# -> (# (# #) | | #)
  _ -> case x ==# 0# of
    1# -> (# | 17# | #)
    _ -> (# | | int2Word# x #)

threeWayCase :: Int# -> Int#
threeWayCase x = case threeWaySum x of
  (# (# #) | | #) -> 13#
  (# | a | #) -> a
  (# | | b #) -> word2Int# b

newtype SumAlias = SumAlias (# State# RealWorld | (# #) #)
{-# OPAQUE aliasIdentity #-}
aliasIdentity :: SumAlias -> SumAlias
aliasIdentity x = x

{-# OPAQUE runtimePolymorphic #-}
runtimePolymorphic :: forall (r :: RuntimeRep) (a :: TYPE r). Int -> (# a | Int# #)
runtimePolymorphic x = raise# x

{-# OPAQUE levityPolymorphic #-}
levityPolymorphic :: forall (l :: Levity) (a :: TYPE ('BoxedRep l)). Int -> (# a | Int# #)
levityPolymorphic x = raise# x

{-# OPAQUE abstractSumIdentity #-}
abstractSumIdentity :: forall (a :: TYPE ('SumRep '[ 'IntRep, 'WordRep])). a -> a
abstractSumIdentity x = x

{-# OPAQUE abstractRuntimeSum #-}
abstractRuntimeSum :: forall (r :: RuntimeRep) (a :: TYPE ('SumRep '[r, 'IntRep])). Int -> a
abstractRuntimeSum x = raise# x

{-# OPAQUE abstractAlternative #-}
abstractAlternative :: forall (a :: TYPE ('TupleRep '[ 'IntRep])). (# a | Int# #) -> (# a | Int# #)
abstractAlternative x = x

-- Known physical slots do not imply runtime support for these leaf families.
{-# OPAQUE addressResult #-}
addressResult :: Int -> (# Addr# | Int# #)
addressResult x = raise# x

{-# OPAQUE vectorResult #-}
vectorResult :: Int -> (# Int32X4# | Int64X2# #)
vectorResult x = raise# x
