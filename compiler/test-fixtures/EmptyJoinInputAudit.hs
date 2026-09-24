-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
{-# OPTIONS_GHC -fno-full-laziness -fno-worker-wrapper -fno-specialise -fno-spec-constr #-}
module EmptyJoinInputAudit where
import GHC.Exts

data Box = Box Int#
{-# OPAQUE bottomBox #-}
bottomBox :: Box
bottomBox = bottomBox

{-# OPAQUE branchCase #-}
branchCase :: Int# -> Int#
branchCase x =
  let {-# NOINLINE finish #-}
      finish u n = case u of (# #) -> n +# 7#
  in case x <=# 0# of
       1# -> finish (# #) (x -# 31#)
       _ -> finish (# #) (x +# 41#)

{-# OPAQUE swapDepth #-}
swapDepth :: Int# -> Int#
swapDepth depth =
  let {-# NOINLINE go #-}
      go a u n b = case n <=# 0# of
        1# -> case u of (# #) -> a -# b
        _ -> go b u (n -# 1#) a
  in go 11# (# #) depth 29#
{-# OPAQUE swapCase #-}
swapCase :: Int# -> Int#
swapCase x = swapDepth (word2Int# (and# (int2Word# x) 31##))

{-# OPAQUE mutualDepth #-}
mutualDepth :: Int# -> Int#
mutualDepth depth =
  let {-# NOINLINE even #-}
      even u n a = case n <=# 0# of
        1# -> case u of (# #) -> a +# 11#
        _ -> odd u (n -# 1#) (a +# 2#)
      {-# NOINLINE odd #-}
      odd u n a = case n <=# 0# of
        1# -> case u of (# #) -> a +# 13#
        _ -> even u (n -# 1#) (a +# 5#)
  in even (# #) depth 5#
{-# OPAQUE mutualCase #-}
mutualCase :: Int# -> Int#
mutualCase x = mutualDepth (word2Int# (and# (int2Word# x) 31##))

{-# OPAQUE nestedCase #-}
nestedCase :: Int# -> Int#
nestedCase x =
  let {-# NOINLINE finish #-}
      finish u n = case u of (# #) -> n +# 17#
  in let {-# NOINLINE inner #-}
         inner n u = case n <=# 0# of
           1# -> finish u (n -# 5#)
           _ -> finish u (n +# 9#)
     in inner x (# #)

{-# OPAQUE tupleResult #-}
tupleResult :: Int# -> (# Int#, Box #)
tupleResult x =
  let {-# NOINLINE finish #-}
      finish :: Int# -> (# #) -> Box -> (# Int#, Box #)
      finish n u box = case u of (# #) -> (# n +# 7#, box #)
  in case x <=# 0# of
       1# -> finish (x -# 3#) (# #) bottomBox
       _ -> finish (x +# 5#) (# #) bottomBox
{-# OPAQUE lazyCase #-}
lazyCase :: Int# -> Int#
lazyCase x = case tupleResult x of (# n, _ #) -> n

-- The empty result has a real write effect, despite having no payload slots.
{-# OPAQUE effectEmpty #-}
effectEmpty :: MutableByteArray# s -> Int# -> State# s -> (# #)
effectEmpty array raw state =
  case writeWord8Array# array 0# (wordToWord8# (int2Word# raw)) state of _ -> (# #)
{-# OPAQUE effectCase #-}
effectCase :: Int# -> Int#
effectCase raw = runRW# (\state ->
  case newByteArray# 1# state of { (# s1, array #) ->
    let {-# NOINLINE finish #-}
        finish u token = case u of
          (# #) -> case unsafeFreezeByteArray# array token of { (# _, frozen #) ->
            word2Int# (word8ToWord# (indexWord8Array# frozen 0#)) }
    in finish (effectEmpty array raw s1) s1 })

{-# OPAQUE checked #-}
checked :: Int# -> Int#
checked x = case x <# 0# of 1# -> raise# bottomBox; _ -> x
{-# OPAQUE checkedEmpty #-}
checkedEmpty :: Int# -> (# #)
checkedEmpty x = case checked x of _ -> (# #)
{-# OPAQUE throwCase #-}
throwCase :: Int# -> Int#
throwCase x =
  let {-# NOINLINE finish #-}
      finish u n = case u of (# #) -> n +# 101#
  in finish (checkedEmpty x) x
