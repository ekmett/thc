{-# LANGUAGE DataKinds, MagicHash, NoImplicitPrelude, StandaloneKindSignatures #-}
{-# LANGUAGE UnboxedTuples, UnliftedDatatypes #-}
-- Result-only tuple boundaries: every executable oracle entry is Int# -> Int#.
-- OPAQUE retains cross-root producers; lifted fields are deliberately lazy.
module TupleReturnAudit where
import GHC.Exts (Int#, TYPE, RuntimeRep(BoxedRep), Levity(Unlifted),
                 (+#), (-#), (*#), (<=#), and#, int2Word#, word2Int#)

data Box = Box Int#
type UnliftedProduct :: TYPE ('BoxedRep 'Unlifted)
data UnliftedProduct = UnliftedProduct Int# Box

{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

{-# OPAQUE bottomFunction #-}
bottomFunction :: Int# -> Int#
bottomFunction = bottomFunction

{-# OPAQUE empty #-}
empty :: Int# -> (# #)
empty _ = (# #)

{-# OPAQUE emptyForward #-}
emptyForward :: Int# -> (# #)
emptyForward x = empty (x +# 1#)

{-# OPAQUE emptyCase #-}
emptyCase :: Int# -> Int#
emptyCase x = case emptyForward x of (# #) -> x +# 17#

{-# OPAQUE singleInt #-}
singleInt :: Int# -> (# Int# #)
singleInt x = (# x +# 257# #)

{-# OPAQUE singleIntForward #-}
singleIntForward :: Int# -> (# Int# #)
singleIntForward x = singleInt (x -# 13#)

{-# OPAQUE singleIntCase #-}
singleIntCase :: Int# -> Int#
singleIntCase x = case singleIntForward x of (# a #) -> a *# 3#

{-# OPAQUE singleBox #-}
singleBox :: Int# -> (# Box #)
singleBox x = case x <=# 0# of
  1# -> (# bottomBox #)
  _ -> (# Box (x +# 31#) #)

{-# OPAQUE singleBoxCase #-}
singleBoxCase :: Int# -> Int#
singleBoxCase x = case singleBox x of
  (# box #) -> case x <=# 0# of
    1# -> x -# 5#
    _ -> case box of Box n -> n *# 7#

{-# OPAQUE addTo #-}
addTo :: Int# -> Int# -> Int#
addTo x y = x +# y *# 3#

{-# OPAQUE singleClosure #-}
singleClosure :: Int# -> (# Int# -> Int# #)
singleClosure x = case x <=# 0# of
  1# -> (# bottomFunction #)
  _ -> (# addTo x #)

{-# OPAQUE singleClosureCase #-}
singleClosureCase :: Int# -> Int#
singleClosureCase x = case singleClosure x of
  (# f #) -> case x <=# 0# of
    1# -> x +# 9#
    _ -> f (x -# 17#)

{-# OPAQUE nested #-}
nested :: Int# -> (# (# Int#, Int# #), (# (# #), Int# #) #)
nested x = (# (# x +# 1#, x -# 3# #), (# (# #), x *# 5# #) #)

{-# OPAQUE nestedCase #-}
nestedCase :: Int# -> Int#
nestedCase x = case nested x of
  (# (# a, b #), (# _, c #) #) -> a +# b *# 7# +# c *# 11#

{-# OPAQUE unliftedBoxedLeaf #-}
unliftedBoxedLeaf :: Int# -> (# UnliftedProduct, Int# #)
unliftedBoxedLeaf x = (# UnliftedProduct (x +# 19#) bottomBox, x -# 23# #)

{-# OPAQUE unliftedBoxedLeafCase #-}
unliftedBoxedLeafCase :: Int# -> Int#
unliftedBoxedLeafCase x = case unliftedBoxedLeaf x of
  (# product, n #) -> case product of UnliftedProduct a _ -> a *# 5# +# n *# 3#

{-# INLINE boundedDepth #-}
boundedDepth :: Int# -> Int#
boundedDepth x = word2Int# (and# (int2Word# x) 31##)

{-# OPAQUE selfTail #-}
selfTail :: Int# -> Int# -> (# Int#, Int# #)
selfTail n acc = case n <=# 0# of
  1# -> (# acc, acc +# 7# #)
  _ -> selfTail (n -# 1#) (acc +# 3#)

{-# OPAQUE selfTailCase #-}
selfTailCase :: Int# -> Int#
selfTailCase x = case selfTail (boundedDepth x) x of (# a, b #) -> a *# 5# +# b *# 7#

{-# OPAQUE selfTailDepth #-}
selfTailDepth :: Int# -> Int#
selfTailDepth n = case selfTail n 5# of (# a, b #) -> a *# 5# +# b *# 7#

{-# OPAQUE mutualA #-}
mutualA :: Int# -> Int# -> (# Int#, Int# #)
mutualA n acc = case n <=# 0# of
  1# -> (# acc, acc +# 11# #)
  _ -> mutualB (n -# 1#) (acc +# 2#)

{-# OPAQUE mutualB #-}
mutualB :: Int# -> Int# -> (# Int#, Int# #)
mutualB n acc = case n <=# 0# of
  1# -> (# acc, acc +# 13# #)
  _ -> mutualA (n -# 1#) (acc +# 5#)

{-# OPAQUE mutualTailCase #-}
mutualTailCase :: Int# -> Int#
mutualTailCase x = case mutualA (boundedDepth x) x of (# a, b #) -> a *# 3# +# b *# 7#

{-# OPAQUE mutualTailDepth #-}
mutualTailDepth :: Int# -> Int#
mutualTailDepth n = case mutualA n 5# of (# a, b #) -> a *# 3# +# b *# 7#

{-# OPAQUE pair #-}
pair :: Int# -> (# Int#, Int# #)
pair x = (# x +# 17#, x -# 23# #)

{-# OPAQUE nonTailTuple #-}
nonTailTuple :: Int# -> (# (# Int#, Int# #), Int# #)
nonTailTuple x = case pair x of
  (# a, b #) -> case singleInt (x +# 11#) of
    (# c #) -> (# (# a *# 3# +# c, b *# 7# -# c #), x #)

{-# OPAQUE nonTailCase #-}
nonTailCase :: Int# -> Int#
nonTailCase x = case nonTailTuple x of (# (# a, b #), c #) -> a *# 5# +# b *# 11# +# c *# 13#

{-# OPAQUE prefixed #-}
prefixed :: Int# -> Int# -> (# Int#, Int# #)
prefixed bias x = (# x +# bias, x -# bias #)

{-# OPAQUE applyTuple #-}
applyTuple :: (Int# -> (# Int#, Int# #)) -> Int# -> (# Int#, Int# #)
applyTuple f x = f x

{-# OPAQUE papCase #-}
papCase :: Int# -> Int#
papCase x = case applyTuple (prefixed (x +# 3#)) (x -# 5#) of (# a, b #) -> a *# 7# +# b *# 11#

{-# OPAQUE opaqueFunction #-}
opaqueFunction :: (Int# -> (# Int#, Int# #)) -> (Int# -> (# Int#, Int# #))
opaqueFunction f = f

{-# OPAQUE overapplicationCase #-}
overapplicationCase :: Int# -> Int#
overapplicationCase x = case opaqueFunction (prefixed (x +# 3#)) (x -# 5#) of
  (# a, b #) -> a *# 7# +# b *# 11#
