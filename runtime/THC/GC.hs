-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Trustworthy #-}

-- |
-- Module      : THC.GC
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : GHC FFI; THC runtime services or native fallback implementation
--
-- JVM-wide collector statistics. These queries neither force a collection
-- nor enable monitoring. Values are independently sampled and cumulative.
module THC.GC (Available(..), CollectorStats(..), collectors) where

import Data.Int (Int64)
import Data.Word (Word64)
import THC.Internal.RuntimeABI

-- | One JVM collector's name and cumulative counters, each independently
-- available. A collector can disappear or decline to expose a counter.
data CollectorStats = CollectorStats
  { collectorName :: Available String
  , collectionCount :: Available Word64
  , collectionMilliseconds :: Available Word64
    -- ^ Approximate elapsed collection time, not pause time or CPU time.
  } deriving (Eq, Show)

-- | Enumerate collectors. Individual optional/invalidated counters retain
-- their own status. An empty available list is different from no JVM support.
collectors :: IO (Available [CollectorStats])
collectors = do
  count <- query 300 0 0 :: IO (Available Int64)
  case count of
    Available size -> Available <$> traverse readCollector [0 .. size - 1]
    Unsupported -> pure Unsupported
    Disabled -> pure Disabled
    Denied -> pure Denied
    Unavailable -> pure Unavailable
  where
    readCollector index = CollectorStats
      <$> queryText 301 index <*> queryWord64 302 index 0 <*> queryWord64 303 index 0
