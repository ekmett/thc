-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Unsafe #-}

-- | Unstable, potentially hazardous Graal diagnostics. This module is
-- deliberately Unsafe for Safe Haskell and is not re-exported by @THC@.
-- Enabling callbacks affects compilation behavior and overhead; the Graal
-- integration is version-pinned. No Java handles or forced recompilation are
-- exposed. Counters are context-attributed, not JVM-wide.
module THC.Internal.JIT
  ( Available(..), JitSnapshot(..), jitTelemetryEnabled
  , setJitTelemetryEnabled, jitSnapshot
  ) where

import Data.Word (Word64)
import THC.Internal.RuntimeABI

-- | Independently sampled cumulative event counts during enabled periods.
-- Disabling does not reset counters. These are callbacks, not a count of
-- currently installed call targets or proof that a particular entry compiled.
-- All tiers/recompilations count. An enabled-period boundary can separate a
-- start from its completion, so matching totals are not guaranteed. Counts
-- saturate at 2^63-1.
data JitSnapshot = JitSnapshot
  { compilationsStarted :: Available Word64
  , compilationsSucceeded :: Available Word64
  , compilationsFailed :: Available Word64
  , invalidations :: Available Word64
  , deoptimizations :: Available Word64
  , compilationsQueued :: Available Word64
  } deriving (Eq, Show)

jitTelemetryEnabled :: IO (Available Bool)
jitTelemetryEnabled = queryEnum 400 [(0, False), (1, True)]

-- | Explicitly opt this context into or out of telemetry. Does not force
-- compilation or enable process-wide monitoring; counters survive toggling.
setJitTelemetryEnabled :: Bool -> IO (Available ())
setJitTelemetryEnabled enabled = control 400 (if enabled then 1 else 0)

jitSnapshot :: IO JitSnapshot
jitSnapshot = JitSnapshot
  <$> queryWord64 401 0 0 <*> queryWord64 402 0 0 <*> queryWord64 403 0 0
  <*> queryWord64 404 0 0 <*> queryWord64 405 0 0 <*> queryWord64 406 0 0
