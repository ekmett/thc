-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE DeriveFunctor, Safe #-}

-- | Shared public result type, re-exported by the service modules.
module THC.Runtime.Types (Availability(..)) where

-- | An absent metric is not zero. Queries never enable JVM-wide monitoring.
data Availability a
  = Available a -- ^ A genuine value; its scope is specified by the query.
  | Unsupported -- ^ The runtime or provider does not implement this service.
  | Disabled -- ^ Supported, but its instrumentation or sink is switched off.
  | Denied -- ^ The current context or host security policy forbids access.
  | Unavailable -- ^ Normally supported, but no value is currently available.
  deriving (Eq, Ord, Show, Functor)
