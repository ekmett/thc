-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveFunctor, Safe #-}

-- |
-- Module      : THC.Runtime.Types
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC (Safe Haskell, derived Functor)
--
-- Shared public result type, re-exported by the service modules.
module THC.Runtime.Types (Available(..)) where

-- | An absent metric is not zero. Queries never enable JVM-wide monitoring.
-- 'fmap' transforms a genuine value and preserves every non-value status:
--
-- >>> fmap (+ 1) (Available (41 :: Int))
-- Available 42
-- >>> fmap (+ 1) (Disabled :: Available Int)
-- Disabled
-- >>> map (fmap (+ 1)) [Unsupported, Disabled, Denied, Unavailable :: Available Int]
-- [Unsupported,Disabled,Denied,Unavailable]
data Available a
  = Available a -- ^ A genuine value; its scope is specified by the query.
  | Unsupported -- ^ The runtime or provider does not implement this service.
  | Disabled -- ^ Supported, but its instrumentation or sink is switched off.
  | Denied -- ^ The current context or host security policy forbids access.
  | Unavailable -- ^ Normally supported, but no value is currently available.
  deriving (Eq, Ord, Show, Functor)
