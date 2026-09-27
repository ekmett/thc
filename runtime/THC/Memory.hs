-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Trustworthy #-}

-- |
-- Module      : THC.Memory
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; THC runtime services or native fallback implementation
--
-- Read-only memory accounting. A record contains independently sampled
-- fields, not an atomic snapshot. All sizes are bytes.
module THC.Memory
  ( Available(..), MemoryUsage(..), NativeAllocationUsage(..)
  , heapUsage, nonHeapUsage, nativeAllocationUsage
  ) where

import Data.Word (Word64)
import THC.Internal.RuntimeABI

-- | JVM-wide usage. Unknown maximum/initial sizes are 'Unavailable', not zero.
data MemoryUsage = MemoryUsage
  { usedBytes :: Available Word64
  , committedBytes :: Available Word64
  , maximumBytes :: Available Word64
  , initialBytes :: Available Word64
  } deriving (Eq, Show)

-- | Only live allocations owned by the current THC context's libc allocator.
-- Counts requested bytes; excludes other Sulong/native/JVM/pinned allocations
-- and allocator overhead. Includes allocations awaiting outstanding borrowers
-- before release. This is not process RSS or total native memory.
data NativeAllocationUsage = NativeAllocationUsage
  { nativeRequestedBytes :: Available Word64
  , nativeAllocationCount :: Available Word64
  } deriving (Eq, Show)

-- | Sample JVM heap usage. Unsupported or unknown quantities keep their status
-- rather than becoming zero; this does not trigger garbage collection.
heapUsage :: IO MemoryUsage
heapUsage = memoryUsage 200

-- | Sample JVM non-heap usage, with the same per-field status contract as
-- 'heapUsage'. This is not the context's native-allocation accounting.
nonHeapUsage :: IO MemoryUsage
nonHeapUsage = memoryUsage 204

memoryUsage :: Int -> IO MemoryUsage
memoryUsage selector = MemoryUsage
  <$> queryWord64 selector 0 0 <*> queryWord64 (selector + 1) 0 0
  <*> queryWord64 (selector + 2) 0 0 <*> queryWord64 (selector + 3) 0 0

-- | Sample live requested bytes and allocation count for the current context's
-- libc allocator. See @NativeAllocationUsage@ for the accounting exclusions.
nativeAllocationUsage :: IO NativeAllocationUsage
nativeAllocationUsage = NativeAllocationUsage <$> queryWord64 208 0 0 <*> queryWord64 209 0 0
