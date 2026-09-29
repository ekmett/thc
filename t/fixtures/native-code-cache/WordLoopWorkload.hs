-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash #-}

-- | Word cases, a local recursive join and global self recursion for code-cache tests.
module WordLoopWorkload (sumFrom, countDown) where

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

-- | Count a nonnegative machine word down to zero using ordinary self recursion.
{-# OPAQUE countDown #-}
countDown :: Int# -> Int#
countDown n = case n of
  0# -> 0#
  _ -> countDown (n -# 1#)
