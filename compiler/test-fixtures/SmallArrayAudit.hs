-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module SmallArrayAudit where

import GHC.Exts

-- One native/guest entry exercises each fundamental SmallArray primitive.
-- The recursive initializer must be stored without being entered.
{-# OPAQUE smallComposite #-}
smallComposite :: Int# -> Int#
smallComposite x = case runRW# (\s ->
  let bottom :: Int
      bottom = bottom
  in case newSmallArray# (x +# 3#) bottom s of
    (# s1, mutable #) -> case sizeofSmallMutableArray# mutable of
      mutableSize -> case getSizeofSmallMutableArray# mutable s1 of
        (# s2, reportedSize #) ->
          case writeSmallArray# mutable 0# (I# x) s2 of
            s3 -> case writeSmallArray# mutable 1# (I# (x +# 7#)) s3 of
              s4 -> case readSmallArray# mutable 1# s4 of
                (# s5, snapshot #) ->
                  case writeSmallArray# mutable 1# (I# (x +# 19#)) s5 of
                    s6 -> case unsafeFreezeSmallArray# mutable s6 of
                      (# s7, frozen #) -> case sizeofSmallArray# frozen of
                        frozenSize -> case indexSmallArray# frozen 0# of
                          (# first #) -> case indexSmallArray# frozen 1# of
                            (# current #) -> case snapshot of
                              I# old -> case first of
                                I# initial -> case current of
                                  I# now -> case cloneSmallArray# frozen 0# 3# of
                                    frozenClone -> case unsafeThawSmallArray# frozenClone s7 of
                                      (# s8, thawed #) -> case writeSmallArray# thawed 1# (I# (x +# 23#)) s8 of
                                        s9 -> case writeSmallArray# thawed 2# (I# (x +# 31#)) s9 of
                                          s10 -> case cloneSmallMutableArray# thawed 0# 3# s10 of
                                            (# s11, cloned #) -> case copySmallMutableArray# cloned 0# cloned 1# 2# s11 of
                                              s12 -> case copySmallArray# frozen 1# cloned 0# 1# s12 of
                                                s13 -> case readSmallArray# cloned 0# s13 of
                                                  (# s14, clone0 #) -> case readSmallArray# cloned 1# s14 of
                                                    (# s15, clone1 #) -> case readSmallArray# cloned 2# s15 of
                                                      (# s16, clone2 #) -> case readSmallArray# thawed 1# s16 of
                                                        (# s17, thawed1 #) -> case indexSmallArray# frozen 1# of
                                                          (# original1 #) -> case clone0 of
                                                            I# a -> case clone1 of
                                                              I# b -> case clone2 of
                                                                I# c -> case thawed1 of
                                                                  I# d -> case original1 of
                                                                    I# e -> I# (old *# 3# +# now *# 5# +#
                                                                      initial *# 11# +#
                                                                      (mutableSize +# reportedSize +# frozenSize) *# 7# +#
                                                                      a *# 2# +# b *# 3# +# c *# 5# +# d *# 7# +# e *# 11#)
  ) of I# result -> result
