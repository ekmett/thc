-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module BoxedArrayExtensionsAudit where

import GHC.Exts

fill :: MutableArray# s Int -> Int# -> Int# -> Int# -> State# s -> State# s
fill a seed n i s = case i ==# n of
  1# -> s
  _ -> case writeArray# a i (I# (seed +# i)) s of
    s1 -> fill a seed n (i +# 1#) s1

weighted :: MutableArray# s Int -> Int# -> Int# -> Int# -> State# s -> (# State# s, Int# #)
weighted a n i total s = case i ==# n of
  1# -> (# s, total #)
  _ -> case readArray# a i s of
    (# s1, I# x #) -> weighted a n (i +# 1#) (total +# (x *# (i +# 1#))) s1

boxedExtSizes :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
boxedExtSizes _ n _ _ _ = runRW# (\s ->
  case newArray# n (I# 0#) s of
    (# s1, a #) -> case sizeofMutableArray# a of
      m -> case unsafeFreezeArray# a s1 of
        (# _, frozen #) -> m +# sizeofArray# frozen)
{-# OPAQUE boxedExtSizes #-}

boxedExtClone :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
boxedExtClone seed n start _ count = runRW# (\s ->
  case newArray# n (I# 0#) s of
    (# s1, a #) -> case fill a seed n 0# s1 of
      s2 -> case cloneMutableArray# a start count s2 of
        (# s3, b #) ->
          let s4 = case count ==# 0# of
                1# -> s3
                _ -> writeArray# b 0# (I# (seed +# 77#)) s3
          in case weighted a n 0# 0# s4 of
            (# s5, x #) -> case weighted b count 0# 0# s5 of
              (# _, y #) -> (x *# 3#) +# (y *# 5#))
{-# OPAQUE boxedExtClone #-}

boxedExtCopy :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
boxedExtCopy seed n from to count = runRW# (\s ->
  case newArray# n (I# 0#) s of
    (# s1, a #) -> case fill a seed n 0# s1 of
      s2 -> case unsafeFreezeArray# a s2 of
        (# s3, frozen #) -> case newArray# n (I# 0#) s3 of
          (# s4, b #) -> case fill b (seed +# 100#) n 0# s4 of
            s5 -> case copyArray# frozen from b to count s5 of
              s6 -> case weighted b n 0# 0# s6 of (# _, answer #) -> answer)
{-# OPAQUE boxedExtCopy #-}

boxedExtMove :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
boxedExtMove seed n from to count = runRW# (\s ->
  case newArray# n (I# 0#) s of
    (# s1, a #) -> case fill a seed n 0# s1 of
      s2 -> case copyMutableArray# a from a to count s2 of
        s3 -> case weighted a n 0# 0# s3 of (# _, answer #) -> answer)
{-# OPAQUE boxedExtMove #-}

boxedExtThaw :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
boxedExtThaw seed n _ to count = runRW# (\s ->
  case newArray# n (I# 0#) s of
    (# s1, a #) -> case fill a seed n 0# s1 of
      s2 -> case unsafeFreezeArray# a s2 of
        (# s3, frozen #) -> case unsafeThawArray# frozen s3 of
          (# s4, b #) ->
            let s5 = case count ==# 0# of
                  1# -> s4
                  _ -> writeArray# b to (I# (seed +# 77#)) s4
            in case weighted b n 0# 0# s5 of (# _, answer #) -> answer)
{-# OPAQUE boxedExtThaw #-}

-- The untouched second element is genuinely bottom. Cloning and overlapping
-- copies retrieve references, never evaluate elements; only element zero is read.
boxedExtLazy :: Int# -> Int# -> Int# -> Int# -> Int# -> Int#
boxedExtLazy seed _ _ _ _ = runRW# (\s ->
  let bottom :: Int; bottom = bottom
  in case newArray# 2# bottom s of
    (# s1, a #) -> case writeArray# a 0# (I# seed) s1 of
      s2 -> case cloneMutableArray# a 0# 2# s2 of
        (# s3, b #) -> case copyMutableArray# b 0# a 0# 2# s3 of
          s4 -> case unsafeFreezeArray# b s4 of
            (# s5, frozen #) -> case copyArray# frozen 0# a 0# 2# s5 of
              s6 -> case readArray# a 0# s6 of (# _, I# x #) -> x)
{-# OPAQUE boxedExtLazy #-}
