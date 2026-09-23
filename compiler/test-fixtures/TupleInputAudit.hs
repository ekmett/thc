{-# LANGUAGE MagicHash, UnboxedTuples #-}
module TupleInputAudit where
import GHC.Exts

data Box = Box Int#
data Failure = Failure
{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

{-# OPAQUE consumePair #-}
consumePair :: (# Int#, Int# #) -> Int#
consumePair (# a, b #) = a +# 3# *# b
{-# OPAQUE consumeMixed #-}
consumeMixed :: (# Int#, (# Float#, Double# #), (# #), Box #) -> Int#
consumeMixed (# a, (# b, c #), (# #), boxed #) =
  case boxed of Box d -> a +# float2Int# b +# double2Int# c +# d
{-# OPAQUE consumeLazy #-}
consumeLazy :: (# Box, Int# #) -> Int#
consumeLazy (# _, x #) = x +# 31#
{-# OPAQUE identityPair #-}
identityPair :: (# Int#, Int# #) -> (# Int#, Int# #)
identityPair pair = pair
{-# OPAQUE mapPair #-}
mapPair :: (# Int#, Int# #) -> (# Int#, Int# #)
mapPair (# a, b #) = (# a +# 3#, b -# 5# #)
{-# OPAQUE applyPair #-}
applyPair :: ((# Int#, Int# #) -> Int#) -> (# Int#, Int# #) -> Int#
applyPair f pair = f pair
{-# OPAQUE apply #-}
apply :: (Int# -> Int#) -> Int# -> Int#
apply f x = f x
{-# OPAQUE prefix #-}
prefix :: Int# -> (# Int#, Int# #) -> Int# -> Int#
prefix before (# a, b #) after = before +# a +# 3# *# b +# after
{-# OPAQUE prefixedPair #-}
prefixedPair :: (# Int#, Int# #) -> Int# -> Int#
prefixedPair pair after = consumePair pair +# after
{-# OPAQUE opaqueFunction #-}
opaqueFunction :: ((# Int#, Int# #) -> Int#) -> ((# Int#, Int# #) -> Int#)
opaqueFunction f = f
{-# OPAQUE recur #-}
recur :: (# Int#, Int# #) -> Int# -> Int#
recur (# a, b #) n = case n <=# 0# of
  1# -> a +# b
  _ -> recur (# a +# b, b +# 1# #) (n -# 1#)
{-# OPAQUE consumeState #-}
consumeState :: (# State# RealWorld, (# #), Int# #) -> Int#
consumeState (# _, (# #), x #) = x +# 19#
{-# OPAQUE consumeNested #-}
consumeNested :: (# (# #), (# Int# #) #) -> Int#
consumeNested (# (# #), (# x #) #) = x +# 23#
{-# OPAQUE consumeDead #-}
consumeDead :: (# Int#, Box #) -> Int# -> Int#
consumeDead _ y = y +# 29#
{-# OPAQUE failureCheck #-}
failureCheck :: Int# -> Int#
failureCheck x = case x <# 0# of
  1# -> raise# Failure
  _ -> x
{-# OPAQUE effectState #-}
effectState :: Int# -> State# RealWorld
effectState x = case failureCheck x of _ -> realWorld#

{-# OPAQUE pairCase #-}
pairCase :: Int# -> Int#
pairCase x = consumePair (# x, 7# #)
{-# OPAQUE mixedCase #-}
mixedCase :: Int# -> Int#
mixedCase x = consumeMixed (# x, (# 1.5#, 2.75## #), (# #), Box (x +# 3#) #)
{-# OPAQUE indirectCase #-}
indirectCase :: Int# -> Int#
indirectCase x = applyPair consumePair (# x, 11# #)
{-# OPAQUE prefixCase #-}
prefixCase :: Int# -> Int#
prefixCase x = prefix 5# (# x, 17# #) 13#
{-# OPAQUE papCase #-}
papCase :: Int# -> Int#
papCase x = apply (prefixedPair (# x, 7# #)) 13#
{-# OPAQUE overCase #-}
overCase :: Int# -> Int#
overCase x = opaqueFunction consumePair (# x, 19# #)
{-# OPAQUE lazyCase #-}
lazyCase :: Int# -> Int#
lazyCase x = consumeLazy (# bottomBox, x #)
{-# OPAQUE roundTripCase #-}
roundTripCase :: Int# -> Int#
roundTripCase x = case mapPair (identityPair (# x, x +# 7# #)) of
  (# a, b #) -> consumePair (# a, b #)
{-# OPAQUE selfCase #-}
selfCase :: Int# -> Int#
selfCase x = recur (# x, 2# #) 4#
{-# OPAQUE stateCase #-}
stateCase :: Int# -> Int#
stateCase x = runRW# (\s -> consumeState (# s, (# #), x #))
{-# OPAQUE nestedCase #-}
nestedCase :: Int# -> Int#
nestedCase x = consumeNested (# (# #), (# x #) #)
{-# OPAQUE deadCase #-}
deadCase :: Int# -> Int#
deadCase x = consumeDead (# x +# 1#, bottomBox #) x
{-# OPAQUE effectCase #-}
effectCase :: Int# -> Int#
effectCase x = consumeState (# effectState x, (# #), x #)
{-# OPAQUE pairInputs #-}
pairInputs :: Int# -> Int# -> Int#
pairInputs x y = consumePair (# x, y #)
{-# OPAQUE selfDepth #-}
selfDepth :: Int# -> Int#
selfDepth n = recur (# 5#, 2# #) n
