{-# LANGUAGE MagicHash, NoImplicitPrelude, UnboxedTuples #-}
-- Native-GHC graph fixtures. OPAQUE retains actual cross-root tuple producers.
module TupleRuntimeGraph where
import GHC.Exts (Int#, (+#), (-#), (*#))

data Box = Box Int# Box

{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

{-# OPAQUE pair #-}
pair :: Int# -> Int# -> (# Int#, Int# #)
pair x y = (# x +# 17#, y -# 23# #)

{-# OPAQUE forward #-}
forward :: Int# -> Int# -> (# Int#, Int# #)
forward x y = pair (x -# 13#) (y +# 29#)

{-# OPAQUE pairCase #-}
pairCase :: Int# -> Int# -> Int#
pairCase x y = case pair x y of (# a, b #) -> a *# 7# +# b *# 11#

{-# OPAQUE forwardedCase #-}
forwardedCase :: Int# -> Int# -> Int#
forwardedCase x y = case forward x y of (# a, b #) -> a *# 7# +# b *# 11#

{-# OPAQUE outstandingCase #-}
outstandingCase :: Int# -> Int# -> Int#
outstandingCase x y = case forward x y of
  (# a, b #) -> case pair (y +# 5#) (x -# 7#) of
    (# c, d #) -> a *# 3# +# b *# 5# +# c *# 7# +# d *# 11#

{-# OPAQUE mixed #-}
mixed :: Int# -> Int# -> (# Int#, Int#, Box #)
mixed x y = (# x +# 31#, y -# 19#, Box (x -# y) bottomBox #)

{-# OPAQUE mixedForward #-}
mixedForward :: Int# -> Int# -> (# Int#, Int#, Box #)
mixedForward x y = mixed (x -# 13#) (y +# 29#)

{-# OPAQUE mixedCase #-}
mixedCase :: Int# -> Int# -> Int#
mixedCase x y = case mixedForward x y of
  (# a, b, box #) -> case box of Box n _ -> a *# 3# +# b *# 5# +# n *# 7#

{-# OPAQUE lazyMixed #-}
lazyMixed :: Int# -> Int# -> (# Int#, Int#, Box #)
lazyMixed x y = (# x +# 41#, y -# 43#, bottomBox #)

{-# OPAQUE lazyMixedCase #-}
lazyMixedCase :: Int# -> Int# -> Int#
lazyMixedCase x y = case lazyMixed x y of (# a, b, _ #) -> a *# 3# +# b *# 5#
