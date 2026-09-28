-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- |
-- Module      : THC.CachedHigherOrder
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC primitive integers
--
-- Higher-order ordinary guest calls for the selected native code cache.
module THC.CachedHigherOrder (calculate) where

import GHC.Exts (Int#, (+#), (-#))

data Words = Nil | Cons Int# Words

{-# OPAQUE descending #-}
descending :: Int# -> Words
descending n = case n of
  0# -> Nil
  _ -> Cons n (descending (n -# 1#))

{-# OPAQUE shift #-}
shift :: Int# -> Int# -> Int#
shift offset x = x +# offset

{-# OPAQUE foldWith #-}
foldWith :: (Int# -> Int#) -> Words -> Int# -> Int#
foldWith f xs acc = case xs of
  Nil -> acc
  Cons n rest -> foldWith f rest (acc +# f n)

{-# OPAQUE shared #-}
shared :: Words
shared = descending 3#

-- | Fold a dynamic partial application over a fresh list and a shared lazy CAF.
-- The count must be nonnegative; arithmetic retains machine-word overflow.
{-# OPAQUE calculate #-}
calculate :: Int# -> Int# -> Int# -> Int#
calculate count offset seed =
  foldWith (shift offset) (descending count) (seed +# foldWith (shift 1#) shared 0#)
