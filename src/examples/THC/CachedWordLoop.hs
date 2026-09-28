-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | Word cases and a local recursive join for the experimental code cache.
module THC.CachedWordLoop (sumFrom) where

import GHC.Exts (Int#, (+#), (-#))

-- | Add the integers from one through a nonnegative count to the seed.
-- The count must be nonnegative; arithmetic has machine-word overflow semantics.
{-# OPAQUE sumFrom #-}
sumFrom :: Int# -> Int# -> Int#
sumFrom n seed =
  let go remaining accumulator = case remaining of
        0# -> accumulator
        _ -> go (remaining -# 1#) (accumulator +# (n -# remaining +# 1#))
  in go n seed
