-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples, UnboxedSums #-}
module SumResultAudit where
import GHC.Exts

data Box = Box Int# Box
data Failure = Failure

{-# OPAQUE produce #-}
produce :: Int# -> (# Int# | Word# #)
produce x = case x <# 0# of 1# -> (# x | #); _ -> (# | int2Word# (x +# 1#) #)
{-# OPAQUE forward #-}
forward :: Int# -> (# Int# | Word# #)
forward x = produce (x +# 1#)
{-# OPAQUE forwardCase #-}
forwardCase :: Int# -> Int#
forwardCase x = case forward x of (# a | #) -> a -# 3#; (# | b #) -> word2Int# b +# 7#

{-# OPAQUE outstandingCase #-}
outstandingCase :: Int# -> Int#
outstandingCase x = case forward x of
  (# a | #) -> case forward (x +# 3#) of (# b | #) -> 3# *# a +# 7# *# b; (# | b #) -> 3# *# a +# 11# *# word2Int# b
  (# | a #) -> case forward (x +# 3#) of (# b | #) -> 5# *# word2Int# a +# 7# *# b; (# | b #) -> 5# *# word2Int# a +# 11# *# word2Int# b

{-# OPAQUE paired #-}
paired :: Int# -> Int# -> (# (# Int#, Int# #) | (# Int#, Int# #) #)
paired x y = case x <# y of 1# -> (# (# x, y #) | #); _ -> (# | (# y, x +# 1# #) #)
{-# OPAQUE pairedInputs #-}
pairedInputs :: Int# -> Int# -> Int#
pairedInputs x y = case paired x y of (# (# a,b #) | #) -> 3# *# a +# 7# *# b; (# | (# a,b #) #) -> 11# *# a +# 13# *# b
{-# OPAQUE pairedCase #-}
pairedCase :: Int# -> Int#
pairedCase x = pairedInputs x (x +# 2#)

{-# OPAQUE lazyLeaf #-}
lazyLeaf :: Int# -> (# Box | Int# #)
lazyLeaf x = case x <# 0# of 1# -> let bottom = bottom in (# bottom | #); _ -> (# | x +# 9# #)
{-# OPAQUE lazyLeafCase #-}
lazyLeafCase :: Int# -> Int#
lazyLeafCase x = case lazyLeaf x of (# _ | #) -> 17#; (# | y #) -> y

{-# OPAQUE mixed #-}
mixed :: Int# -> State# RealWorld -> (# (# State# RealWorld, Int#, Float# #) | (# Double#, Box #) #)
mixed x s = case x <# 0# of
  1# -> (# (# s, x, 2.5# #) | #)
  _ -> (# | (# 3.5##, Box x (raise# Failure) #) #)
{-# OPAQUE mixedCase #-}
mixedCase :: Int# -> Int#
mixedCase x = runRW# (\s -> case mixed x s of
  (# (# _, a, f #) | #) -> a +# float2Int# f
  (# | (# d, Box a _ #) #) -> a +# double2Int# d)

{-# OPAQUE selfSum #-}
selfSum :: Int# -> Int# -> (# Int# | Word# #)
selfSum n x = case n ==# 0# of 1# -> produce x; _ -> selfSum (n -# 1#) (x +# 1#)
{-# OPAQUE selfCase #-}
selfCase :: Int# -> Int#
selfCase x = case selfSum 5# x of (# a | #) -> a; (# | a #) -> word2Int# a
{-# OPAQUE mutualA #-}
mutualA :: Int# -> Int# -> (# Int# | Word# #)
mutualA n x = case n ==# 0# of 1# -> produce x; _ -> mutualB (n -# 1#) (x +# 2#)
{-# OPAQUE mutualB #-}
mutualB :: Int# -> Int# -> (# Int# | Word# #)
mutualB n x = case n ==# 0# of 1# -> produce x; _ -> mutualA (n -# 1#) (x -# 1#)
{-# OPAQUE mutualCase #-}
mutualCase :: Int# -> Int#
mutualCase x = case mutualA 5# x of (# a | #) -> a; (# | a #) -> word2Int# a

{-# OPAQUE failState #-}
failState :: Int# -> State# RealWorld
failState x = case x <# 0# of 1# -> raise# Failure; _ -> realWorld#
{-# OPAQUE effectState #-}
effectState :: Int# -> (# State# RealWorld | (# #) #)
effectState x = (# failState x | #)
{-# OPAQUE effectEmpty #-}
effectEmpty :: Int# -> (# State# RealWorld | (# #) #)
effectEmpty x = case failState x of _ -> (# | (# #) #)
{-# OPAQUE effectStateCase #-}
effectStateCase :: Int# -> Int#
effectStateCase x = case effectState x of (# _ | #) -> x +# 21#; (# | _ #) -> 0#
{-# OPAQUE effectEmptyCase #-}
effectEmptyCase :: Int# -> Int#
effectEmptyCase x = case effectEmpty x of (# _ | #) -> 0#; (# | _ #) -> x +# 23#

{-# OPAQUE singletonBox #-}
singletonBox :: Int# -> (# (# Box #) | Int# #)
singletonBox x = case x <# 0# of
  1# -> let bottom = bottom in (# (# bottom #) | #)
  _ -> (# | x +# 31# #)
{-# OPAQUE singletonBoxCase #-}
singletonBoxCase :: Int# -> Int#
singletonBoxCase x = case singletonBox x of (# (# _ #) | #) -> 29#; (# | a #) -> a

{-# OPAQUE throwSum #-}
throwSum :: Int# -> (# Int# | Word# #)
throwSum x = case x <# 0# of 1# -> raise# Failure; _ -> produce x
{-# OPAQUE throwCase #-}
throwCase :: Int# -> Int#
throwCase x = case throwSum x of (# a | #) -> a; (# | a #) -> word2Int# a
