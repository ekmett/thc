-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | A lazy recursive data structure for the selected native code cache.
module THC.CachedList (sumFrom) where

import GHC.Exts (Int#, (+#), (-#))

data Words = Nil | Cons Int# Words

{-# OPAQUE descending #-}
descending :: Int# -> Words
descending n = case n of
  0# -> Nil
  _ -> Cons n (descending (n -# 1#))

{-# OPAQUE foldWords #-}
foldWords :: Words -> Int# -> Int#
foldWords xs acc = case xs of
  Nil -> acc
  Cons n rest -> foldWords rest (acc +# n)

{-# OPAQUE shared #-}
shared :: Words
shared = descending 3#

-- | Fold a fresh list and a shared lazy list into a dynamic seed.
-- The count must be nonnegative. Arithmetic uses machine-word overflow.
-- Each cache load owns its own shared list and lazy tails.
{-# OPAQUE sumFrom #-}
sumFrom :: Int# -> Int# -> Int#
sumFrom n seed = foldWords (descending n) (seed +# foldWords shared 0#)
