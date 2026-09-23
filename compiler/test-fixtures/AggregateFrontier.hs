{-# LANGUAGE MagicHash, NoImplicitPrelude, UnboxedTuples, UnboxedSums #-}
-- Negative coverage only: these are native GHC programs, not supported THC entries.
-- OPAQUE keeps the producer/call boundaries present under the ordinary -O2 pipeline.
module AggregateFrontier where
import GHC.Exts (Int#, State#, (+#), (-#), (*#), (<=#), (==#))

data Box = Box Int#

{-# OPAQUE pair #-}
pair :: Int# -> (# Int#, Int# #)
pair x = (# x +# 257#, x -# 1025# #)

{-# OPAQUE forward #-}
forward :: Int# -> (# Int#, Int# #)
forward x = pair (x -# 257#)

{-# OPAQUE tupleOutstanding #-}
tupleOutstanding :: Int# -> Int#
tupleOutstanding x = case forward x of
  (# a, b #) -> case pair (x +# 4097#) of
    (# c, d #) -> a +# b *# 3# +# c *# 5# +# d *# 7#

{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

{-# OPAQUE zeroLazy #-}
zeroLazy :: Int# -> (# (# #), Int#, Box #)
zeroLazy x = case x <=# 0# of
  1# -> (# (# #), x +# 4099#, bottomBox #)
  _ -> (# (# #), x +# 4099#, Box (x -# 515#) #)

{-# OPAQUE tupleZeroLazy #-}
tupleZeroLazy :: Int# -> Int#
tupleZeroLazy x = case zeroLazy x of
  (# _, n, box #) -> case x <=# 0# of
    1# -> n
    _ -> case box of Box b -> n +# b *# 3#

{-# OPAQUE sumProducer #-}
sumProducer :: Int# -> (# Int# | (# Int#, Box #) #)
sumProducer x = case x <=# 0# of
  1# -> (# x +# 17# | #)
  _ -> (# | (# x -# 257#, Box (x +# 1025#) #) #)

{-# OPAQUE sumPayload #-}
sumPayload :: Int# -> Int#
sumPayload x = case sumProducer x of
  (# a | #) -> a *# 3#
  (# | (# b, box #) #) -> case box of Box c -> b *# 5# +# c *# 7#

{-# OPAQUE zeroSumProducer #-}
zeroSumProducer :: Int# -> (# (# #) | Box #)
zeroSumProducer x = case x <=# 0# of
  1# -> (# (# #) | #)
  _ -> (# | bottomBox #)

{-# OPAQUE sumZeroLazy #-}
sumZeroLazy :: Int# -> Int#
sumZeroLazy x = case zeroSumProducer x of
  (# _ | #) -> x +# 19#
  (# | _ #) -> x -# 23#

-- The default native inputs never take these arms. Strict loading and the audit
-- must still inspect the retained alternatives before any input is executed.
{-# OPAQUE coldTuple #-}
coldTuple :: Int# -> Int#
coldTuple x = case x ==# 31337# of
  1# -> tupleOutstanding x
  _ -> x +# 5#

{-# OPAQUE coldSum #-}
coldSum :: Int# -> Int#
coldSum x = case x ==# 31337# of
  1# -> sumPayload x
  _ -> x -# 7#

-- No constructor occurs in these identities. Their logical aggregate boundary
-- remains unsupported, including zero and one physical-register tuples.
{-# OPAQUE emptyIdentity #-}
emptyIdentity :: (# #) -> (# #)
emptyIdentity x = x

{-# OPAQUE emptyDiscard #-}
emptyDiscard :: (# #) -> Int#
emptyDiscard _ = 41#

{-# OPAQUE singletonIdentity #-}
singletonIdentity :: (# Int# #) -> (# Int# #)
singletonIdentity x = x

{-# OPAQUE pairIdentity #-}
pairIdentity :: (# Int#, Int# #) -> (# Int#, Int# #)
pairIdentity x = x

{-# OPAQUE sumIdentity #-}
sumIdentity :: (# (# #) | Box #) -> (# (# #) | Box #)
sumIdentity x = x

-- Controls: an abstract lifted value and a zero-width state token are not tuples.
{-# OPAQUE abstractIdentity #-}
abstractIdentity :: a -> a
abstractIdentity x = x

{-# OPAQUE stateIdentity #-}
stateIdentity :: State# s -> State# s
stateIdentity x = x
