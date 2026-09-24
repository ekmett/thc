-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module EmptyTupleInputAudit where
import GHC.Exts

data Box = Box Int#
data Failure = Failure

{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

{-# OPAQUE before #-}
before :: (# #) -> Int# -> Int#
before _ x = x *# 3# +# 7#
{-# OPAQUE beforeBias #-}
beforeBias :: Int# -> (# #) -> Int# -> Int#
beforeBias bias _ x = x *# 3# +# bias +# 7#
{-# OPAQUE between #-}
between :: Int# -> (# #) -> Int# -> Int#
between x _ y = x *# 3# +# y *# 5# +# 11#
{-# OPAQUE after #-}
after :: Int# -> Int# -> (# #) -> Int#
after x y _ = x -# y *# 7#
{-# OPAQUE identityEmpty #-}
identityEmpty :: (# #) -> (# #)
identityEmpty e = e
{-# OPAQUE used #-}
used :: (# #) -> Int# -> Int#
used e x = case identityEmpty e of (# #) -> x +# 19#
{-# OPAQUE emptyOnly #-}
emptyOnly :: (# #) -> (# #) -> Int# -> Int#
emptyOnly _ _ x = x +# 29#
{-# OPAQUE apply #-}
apply :: (Int# -> Int#) -> Int# -> Int#
apply f x = f x
{-# OPAQUE opaqueFunction #-}
opaqueFunction :: ((# #) -> Int# -> Int#) -> ((# #) -> Int# -> Int#)
opaqueFunction f = f
{-# OPAQUE lazyInput #-}
lazyInput :: (# #) -> Box -> Int# -> Int#
lazyInput _ _ x = x +# 31#
{-# OPAQUE pair #-}
pair :: (# #) -> Int# -> (# Int#, Int# #)
pair _ x = (# x +# 13#, x -# 17# #)
{-# OPAQUE failureCheck #-}
failureCheck :: Int# -> Int#
failureCheck x = case x <# 0# of
  1# -> raise# Failure
  _ -> x
{-# OPAQUE effectEmpty #-}
effectEmpty :: Int# -> (# #)
effectEmpty x = case failureCheck x of _ -> (# #)
{-# OPAQUE self #-}
self :: (# #) -> Int# -> Int# -> Int#
self e n acc = case n <=# 0# of
  1# -> acc
  _ -> self e (n -# 1#) (acc +# 3#)
{-# OPAQUE mutualA #-}
mutualA :: (# #) -> Int# -> Int# -> Int#
mutualA e n acc = case n <=# 0# of
  1# -> acc +# 7#
  _ -> mutualB (n -# 1#) e (acc +# 2#)
{-# OPAQUE mutualB #-}
mutualB :: Int# -> (# #) -> Int# -> Int#
mutualB n e acc = case n <=# 0# of
  1# -> acc +# 11#
  _ -> mutualA e (n -# 1#) (acc +# 5#)
{-# INLINE depth #-}
depth :: Int# -> Int#
depth x = word2Int# (and# (int2Word# x) 31##)

{-# OPAQUE scalarBefore #-}
scalarBefore :: Int# -> Int#
scalarBefore x = x *# 3# +# 7#
{-# OPAQUE scalarControl #-}
scalarControl :: Int# -> Int#
scalarControl x = scalarBefore (x +# 1#) -# 3#
{-# OPAQUE betweenInputs #-}
betweenInputs :: Int# -> Int# -> Int#
betweenInputs x y = between x (# #) y

{-# OPAQUE beforeCase #-}
beforeCase :: Int# -> Int#
beforeCase x = before (# #) x
{-# OPAQUE betweenCase #-}
betweenCase :: Int# -> Int#
betweenCase x = between x (# #) (x -# 2#)
{-# OPAQUE afterCase #-}
afterCase :: Int# -> Int#
afterCase x = after x (x +# 3#) (# #)
{-# OPAQUE usedCase #-}
usedCase :: Int# -> Int#
usedCase x = used (# #) x
{-# OPAQUE papEmptyCase #-}
papEmptyCase :: Int# -> Int#
papEmptyCase x = apply (before (# #)) x
{-# OPAQUE papTwoEmptyCase #-}
papTwoEmptyCase :: Int# -> Int#
papTwoEmptyCase x = apply (emptyOnly (# #) (# #)) x
{-# OPAQUE papMixedCase #-}
papMixedCase :: Int# -> Int#
papMixedCase x = apply (between x (# #)) (x +# 1#)
{-# OPAQUE overCase #-}
overCase :: Int# -> Int#
overCase x = opaqueFunction (beforeBias x) (# #) x
{-# OPAQUE lazyCase #-}
lazyCase :: Int# -> Int#
lazyCase x = lazyInput (# #) bottomBox x
{-# OPAQUE pairCase #-}
pairCase :: Int# -> Int#
pairCase x = case pair (# #) x of (# a, b #) -> a *# 3# +# b *# 5#
{-# OPAQUE selfCase #-}
selfCase :: Int# -> Int#
selfCase x = self (# #) (depth x) x
{-# OPAQUE mutualCase #-}
mutualCase :: Int# -> Int#
mutualCase x = mutualA (# #) (depth x) x
{-# OPAQUE selfDepth #-}
selfDepth :: Int# -> Int#
selfDepth n = self (# #) n 5#
{-# OPAQUE mutualDepth #-}
mutualDepth :: Int# -> Int#
mutualDepth n = mutualA (# #) n 5#
-- Negative inputs intentionally raise Failure; native normal rows use nonnegative inputs.
{-# OPAQUE effectCase #-}
effectCase :: Int# -> Int#
effectCase x = before (effectEmpty x) x
{-# OPAQUE effectPapCase #-}
effectPapCase :: Int# -> Int#
effectPapCase x = apply (before (effectEmpty x)) x
