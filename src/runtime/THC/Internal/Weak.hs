-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, Unsafe #-}

-- |
-- Module      : THC.Internal.Weak
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : MagicHash, UnboxedTuples; GHC 9.14.1 finalizer ABI
--
-- Adapt a raw IO action to GHC's original automatic finalizer entry point.
-- Raw state transformers are unsafe; the runtime owns their guest execution.
module THC.Internal.Weak
  ( -- * Automatic finalizer execution
    runWeakFinalizer
  ) where

import GHC.Exts (RealWorld, State#, newArray#, runRW#, unsafeFreezeArray#)
import GHC.Internal.Weak.Finalize (runFinalizerBatch)

-- | Run an original @State# RealWorld -> (# State# RealWorld, a #)@ action
-- through GHC's automatic finalizer policy, discarding its lifted result
-- without evaluating it. Each invocation runs the action once; the action
-- remains reusable. This does not claim or retire a weak registration.
--
-- GHC labels the executing thread, reports action exceptions to its current
-- finalizer exception handler, and ignores exceptions thrown by that handler.
-- The handler is read when an exception occurs, not when this action is built.
-- Callers must supply a genuine IO state transformer and retain its owning
-- runtime context throughout execution; state tokens must not be fabricated.
runWeakFinalizer :: (State# RealWorld -> (# State# RealWorld, a #)) -> IO ()
runWeakFinalizer action = runFinalizerBatch 1 (runRW# $ \s ->
  case newArray# 1# (\s0 -> case action s0 of (# s1, _ #) -> s1) s of
    (# s1, array #) -> case unsafeFreezeArray# array s1 of
      (# _, frozen #) -> frozen)
{-# OPAQUE runWeakFinalizer #-}
