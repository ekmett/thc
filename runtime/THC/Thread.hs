-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, Trustworthy #-}

-- | Current-thread observations. No API exposes a carrier handle, arbitrary
-- host-thread access, raw pinning or Java interruption. Guest forks currently
-- use platform threads; affinity operations refuse virtual-thread callers.
module THC.Thread
  ( Availability(..), ThreadKind(..), ThreadInfo(..), ThreadAccounting(..)
  , CpuCoordinate(..), currentThreadInfo, currentThreadAccounting, eligibleCPUs
  , CpuAffinitySupport(..), cpuAffinitySupport, affinityApplied, forkOnWithAffinity
  ) where

import Control.Concurrent (ThreadId, forkOn)
import Data.Int (Int64)
import Data.Word (Word64)
import Foreign.C.Types (CInt(..))
import THC.Internal.RuntimeABI

data ThreadKind = NativeHaskellThread | PlatformThread | VirtualThread
  deriving (Eq, Ord, Show)

data CpuAffinitySupport = NoCpuAffinity | AdvisoryCpuAffinity | PinnedCpuAffinity
  deriving (Eq, Ord, Show)

-- | Logical lock/request information is not evidence of OS pin acceptance.
data ThreadInfo = ThreadInfo
  { threadKind :: Availability ThreadKind
  , logicalCapability :: Availability Int
  , logicalCapabilityLocked :: Availability Bool
  , threadAffinitySupport :: Availability CpuAffinitySupport
  , threadAffinityApplied :: Availability Bool
  } deriving (Eq, Show)

-- | Cumulative accounting for the currently executing JVM thread. Does not
-- enable monitoring, and is not an allocation/time budget. On a platform
-- thread these counters include other host work performed on that thread.
data ThreadAccounting = ThreadAccounting
  { threadCpuNanoseconds :: Availability Word64
  , threadUserNanoseconds :: Availability Word64
  , threadAllocatedBytes :: Availability Word64
  } deriving (Eq, Show)

-- | OS coordinate: Linux group zero plus sparse OS CPU ID; Windows processor
-- group plus processor number. These are not GHC capability IDs.
data CpuCoordinate = CpuCoordinate
  { cpuGroup :: Int
  , cpuProcessor :: Int
  } deriving (Eq, Ord, Show)

currentThreadInfo :: IO ThreadInfo
currentThreadInfo = ThreadInfo
  <$> queryEnum 100 [(0, NativeHaskellThread), (1, PlatformThread), (2, VirtualThread)]
  <*> query 101 0 0
  <*> queryEnum 102 [(0, False), (1, True)]
  <*> queryEnum 103 [(0, NoCpuAffinity), (1, AdvisoryCpuAffinity), (2, PinnedCpuAffinity)]
  <*> queryEnum 104 [(0, False), (1, True)]

currentThreadAccounting :: IO ThreadAccounting
currentThreadAccounting = ThreadAccounting <$> query 105 0 0 <*> query 106 0 0 <*> query 107 0 0

-- | Initial context eligibility in dense capability order, capped by the
-- JVM's CPU capacity. This is not a live process-affinity query. Unavailable
-- discovery is explicit, rather than a fabricated list of CPU IDs.
eligibleCPUs :: IO (Availability [CpuCoordinate])
eligibleCPUs = do
  count <- query 108 0 0 :: IO (Availability Int64)
  case count of
    Available size -> collect [0 .. size - 1] []
    Unsupported -> pure Unsupported
    Disabled -> pure Disabled
    Denied -> pure Denied
    Unavailable -> pure Unavailable
  where
    collect [] reversed = pure (Available (reverse reversed))
    collect (index:rest) reversed = do
      group <- query 109 index 0
      processor <- query 110 index 0
      case (group, processor) of
        (Available g, Available p) -> collect rest (CpuCoordinate g p : reversed)
        (Unsupported, _) -> pure Unsupported
        (Disabled, _) -> pure Disabled
        (Denied, _) -> pure Denied
        (Unavailable, _) -> pure Unavailable
        (_, Unsupported) -> pure Unsupported
        (_, Disabled) -> pure Disabled
        (_, Denied) -> pure Denied
        (_, Unavailable) -> pure Unavailable

foreign import ccall unsafe "thc_cpu_affinity_v1_support"
  affinitySupportCode :: IO CInt
foreign import ccall unsafe "thc_cpu_affinity_v1_applied"
  affinityAppliedCode :: IO CInt

-- | Compatibility query preserving the original API. Native GHC returns
-- 'NoCpuAffinity': ordinary GHC 'forkOn' promises a capability, not a physical
-- CPU. This does not inspect @+RTS -qa@. For detailed failure status, inspect
-- 'threadAffinitySupport' in 'currentThreadInfo'.
cpuAffinitySupport :: IO CpuAffinitySupport
cpuAffinitySupport = do
  code <- affinitySupportCode
  pure $ case code of
    1 -> AdvisoryCpuAffinity
    2 -> PinnedCpuAffinity
    _ -> NoCpuAffinity

-- | Did this registered THC fork's initial native request succeed? False also
-- covers unavailable support or no request. Acceptance is not a bound-thread
-- TLS guarantee, nor a promise against subsequent OS policy changes.
affinityApplied :: IO Bool
affinityApplied = (/= 0) <$> affinityAppliedCode

-- | Like 'forkOn', with the child's initial native-affinity acceptance. The
-- callback always runs, including unavailable/rejected affinity. Preserves lazy
-- action evaluation and inherited masking. The integer is a capability index,
-- not an OS CPU ID; advisory acceptance remains advisory.
forkOnWithAffinity :: Int -> (Bool -> IO ()) -> IO ThreadId
forkOnWithAffinity capability action = forkOn capability (affinityApplied >>= action)
