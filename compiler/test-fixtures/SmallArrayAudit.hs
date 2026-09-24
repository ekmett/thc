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
                      (# _, frozen #) -> case sizeofSmallArray# frozen of
                        frozenSize -> case indexSmallArray# frozen 0# of
                          (# first #) -> case indexSmallArray# frozen 1# of
                            (# current #) -> case snapshot of
                              I# old -> case first of
                                I# initial -> case current of
                                  I# now -> I# (old *# 3# +# now *# 5# +#
                                    initial *# 11# +#
                                    (mutableSize +# reportedSize +# frozenSize) *# 7#)
  ) of I# result -> result
