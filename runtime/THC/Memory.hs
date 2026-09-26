-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Trustworthy #-}

-- | Read-only memory accounting. A record contains independently sampled
-- fields, not an atomic snapshot. All sizes are bytes.
module THC.Memory
  ( Availability(..), MemoryUsage(..), NativeAllocationUsage(..)
  , heapUsage, nonHeapUsage, nativeAllocationUsage
  ) where

import Data.Word (Word64)
import THC.Internal.RuntimeABI

-- | JVM-wide usage. Unknown maximum/initial sizes are 'Unavailable', not zero.
data MemoryUsage = MemoryUsage
  { usedBytes :: Availability Word64
  , committedBytes :: Availability Word64
  , maximumBytes :: Availability Word64
  , initialBytes :: Availability Word64
  } deriving (Eq, Show)

-- | Only live allocations owned by the current THC context's libc allocator.
-- Counts requested bytes; excludes other Sulong/native/JVM/pinned allocations
-- and allocator overhead. Includes allocations awaiting outstanding borrowers
-- before release. This is not process RSS or total native memory.
data NativeAllocationUsage = NativeAllocationUsage
  { nativeRequestedBytes :: Availability Word64
  , nativeAllocationCount :: Availability Word64
  } deriving (Eq, Show)

heapUsage :: IO MemoryUsage
heapUsage = memoryUsage 200

nonHeapUsage :: IO MemoryUsage
nonHeapUsage = memoryUsage 204

memoryUsage :: Int -> IO MemoryUsage
memoryUsage selector = MemoryUsage
  <$> queryWord64 selector 0 0 <*> queryWord64 (selector + 1) 0 0
  <*> queryWord64 (selector + 2) 0 0 <*> queryWord64 (selector + 3) 0 0

nativeAllocationUsage :: IO NativeAllocationUsage
nativeAllocationUsage = NativeAllocationUsage <$> queryWord64 208 0 0 <*> queryWord64 209 0 0
