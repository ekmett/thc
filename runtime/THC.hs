-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface #-}

-- | Small runtime queries with an ordinary native-GHC implementation.
module THC
  ( CpuAffinitySupport(..), cpuAffinitySupport, affinityApplied, forkOnWithAffinity
  ) where

import Control.Concurrent (ThreadId, forkOn)
import Foreign.C.Types (CInt(..))

-- | What this runtime can request. Support does not guarantee that an
-- individual request will be accepted by the OS or remain effective forever.
data CpuAffinitySupport = NoCpuAffinity | AdvisoryCpuAffinity | PinnedCpuAffinity
  deriving (Eq, Ord, Show)

foreign import ccall unsafe "thc_cpu_affinity_v1_support"
  affinitySupportCode :: IO CInt
foreign import ccall unsafe "thc_cpu_affinity_v1_applied"
  affinityAppliedCode :: IO CInt

-- | Query THC's native-affinity provider. The native-GHC compatibility shim
-- returns 'NoCpuAffinity': ordinary GHC 'forkOn' guarantees a capability, not
-- a physical CPU. This is not a query of GHC's @+RTS -qa@ setting.
cpuAffinitySupport :: IO CpuAffinitySupport
cpuAffinitySupport = do
  code <- affinitySupportCode
  pure $ case code of
    1 -> AdvisoryCpuAffinity
    2 -> PinnedCpuAffinity
    _ -> NoCpuAffinity

-- | Whether the current registered THC fork's initial native affinity request
-- was accepted. False also covers ordinary entries, unavailable support, and
-- failed requests. True is not a bound-thread/TLS guarantee or a promise that
-- external OS policy cannot subsequently change the affinity.
affinityApplied :: IO Bool
affinityApplied = (/= 0) <$> affinityAppliedCode

-- | Like 'forkOn', preserving its lazy child action, inherited masking state
-- and child exception behavior. The callback always runs, including when native
-- affinity is unavailable or fails; its argument reports that child's request.
-- The integer is a capability number, interpreted exactly as by 'forkOn', not
-- an OS CPU identifier. Advisory acceptance remains advisory.
forkOnWithAffinity :: Int -> (Bool -> IO ()) -> IO ThreadId
forkOnWithAffinity capability action = forkOn capability (affinityApplied >>= action)
