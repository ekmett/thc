-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE MagicHash, UnboxedTuples, Unsafe #-}

-- |
-- Module      : THC.Interop.Array.Unsafe
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC polyglot runtime
--
-- Mutable aliases grant foreign code replacement authority over polymorphic
-- Haskell storage. The caller must preserve the element type and must not
-- unsafeFreeze storage while a foreign writer retains the view.
module THC.Interop.Array.Unsafe (unsafeMutableView, unsafeMutableSmallView) where

import GHC.Exts (MutableArray#, SmallMutableArray#, RealWorld)
import GHC.IO (IO(..))
import THC.Internal.Polyglot

-- | Foreign replacements must be same-context lifted guest references of the
-- original element type; arbitrary foreign objects are rejected.
unsafeMutableView :: MutableArray# RealWorld a -> IO Value
unsafeMutableView array = IO $ \state -> case mutableArrayView# array state of
  (# next, value #) -> (# next, Value value #)

-- | Writable small-array alias; shrinking updates its observable length.
unsafeMutableSmallView :: SmallMutableArray# RealWorld a -> IO Value
unsafeMutableSmallView array = IO $ \state -> case mutableSmallArrayView# array state of
  (# next, value #) -> (# next, Value value #)
