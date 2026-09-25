-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples #-}
module ArithmeticExceptionsAudit where

import GHC.Exts

data Answer = Answer Int#

-- Each exceptional arm consumes GHC's genuine empty unboxed tuple. The
-- payload comes from ghc-internal, not from a fixture-defined exception.
{-# OPAQUE divideOrAdd #-}
divideOrAdd :: Int# -> Int#
divideOrAdd raw = case raw ==# 0# of
  1# -> raiseDivZero# (##)
  _ -> raw +# 41#

{-# OPAQUE underflowOrAdd #-}
underflowOrAdd :: Int# -> Int#
underflowOrAdd raw = case raw ==# -1# of
  1# -> raiseUnderflow# (##)
  _ -> raw +# 43#

{-# OPAQUE overflowOrAdd #-}
overflowOrAdd :: Int# -> Int#
overflowOrAdd raw = case raw ==# -2# of
  1# -> raiseOverflow# (##)
  _ -> raw +# 47#

{-# OPAQUE catchDivide #-}
catchDivide :: Int# -> Int#
catchDivide raw = runRW# (\s ->
  case catch# (\s0 -> case divideOrAdd raw of result -> (# s0, Answer result #))
              (\_ s1 -> (# s1, Answer 71# #)) s of
    (# _, Answer result #) -> result)

{-# OPAQUE catchUnderflow #-}
catchUnderflow :: Int# -> Int#
catchUnderflow raw = runRW# (\s ->
  case catch# (\s0 -> case underflowOrAdd raw of result -> (# s0, Answer result #))
              (\_ s1 -> (# s1, Answer 73# #)) s of
    (# _, Answer result #) -> result)

{-# OPAQUE catchOverflow #-}
catchOverflow :: Int# -> Int#
catchOverflow raw = runRW# (\s ->
  case catch# (\s0 -> case overflowOrAdd raw of result -> (# s0, Answer result #))
              (\_ s1 -> (# s1, Answer 79# #)) s of
    (# _, Answer result #) -> result)
