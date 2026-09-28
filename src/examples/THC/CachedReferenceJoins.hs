-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}
-- Keep selection of a possibly-bottom function before its application: otherwise
-- GHC may eta-expand the case and turn the reference join into a numeric one.
{-# OPTIONS_GHC -fno-do-lambda-eta-expansion #-}

-- |
-- Module      : THC.CachedReferenceJoins
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC primitive integers
--
-- Ordinary data- and function-returning local joins for the selected code cache.
module THC.CachedReferenceJoins (calculate) where

import GHC.Exts (Int#, (+#), (-#))

data Box = End | Box Int# Box

{-# OPAQUE shared #-}
shared :: Box
shared = Box 9# End

{-# OPAQUE build #-}
build :: Int# -> Int# -> Box
build count offset =
  let go remaining acc = case remaining of
        0# -> Box acc End
        _ -> go (remaining -# 1#) (acc +# offset +# (count -# remaining +# 1#))
  in go count 0#

{-# OPAQUE select #-}
select :: Int# -> (Box -> Int#) -> (Box -> Int#) -> Box -> Int#
select flag first second =
  let {-# NOINLINE finish #-}
      finish :: (Box -> Int#) -> Box -> Int#
      finish value = case flag of
        0# -> value
        _ -> first
  in case flag of
    0# -> finish second
    _ -> finish first

{-# OPAQUE shift #-}
shift :: Int# -> Box -> Int#
shift offset value = case value of
  End -> offset
  Box x _ -> offset +# x

{-# OPAQUE apply #-}
apply :: (Box -> Int#) -> Box -> Int#
apply f x = f x

-- | Combine a data-returning local loop and a closure-returning local join.
-- The count must be nonnegative; arithmetic has machine-word overflow semantics.
{-# OPAQUE calculate #-}
calculate :: Int# -> Int# -> Int# -> Int#
calculate count offset seed = case shared of
  End -> seed
  Box bias _ -> case build count offset of
    End -> seed
    Box value _ -> apply (select count (shift (seed +# value)) (shift seed)) (Box bias End)
