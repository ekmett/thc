-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

{-# LANGUAGE MagicHash, UnboxedTuples #-}
module THC.BoxedCasCounter (boxedCasCounter) where

import GHC.Exts

data Counter = Counter Int#

-- A CAS loop must retry with the returned observation, not its stale expected
-- value. The payload is strict, but comparison itself is pointer identity.
bump :: MutVar# s Counter -> Counter -> State# s -> State# s
bump cell ticket@(Counter value) state =
  case casMutVar# cell ticket (Counter (value +# 1#)) state of
    (# next, failed, observed #) -> case failed of
      0# -> next
      _ -> bump cell observed next

loop :: Int# -> MutVar# s Counter -> State# s -> Int#
loop remaining cell state = case remaining of
  0# -> case readMutVar# cell state of { (# _, Counter value #) -> value }
  _ -> case readMutVar# cell state of { (# next, ticket #) ->
    loop (remaining -# 1#) cell (bump cell ticket next) }

-- Pure, bounded unary entry for the THC launcher. n mod 64 successful updates.
{-# OPAQUE boxedCasCounter #-}
boxedCasCounter :: Int# -> Int#
boxedCasCounter n = runRW# (\s ->
  case newMutVar# (Counter 0#) s of { (# next, cell #) ->
    loop (word2Int# (and# (int2Word# n) 63##)) cell next })
