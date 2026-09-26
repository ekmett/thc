-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Trustworthy #-}

-- | JVM-wide collector statistics. These queries neither force a collection
-- nor enable monitoring. Values are independently sampled and cumulative.
module THC.GC (Availability(..), CollectorStats(..), collectors) where

import Data.Int (Int64)
import Data.Word (Word64)
import THC.Internal.RuntimeABI

data CollectorStats = CollectorStats
  { collectorName :: Availability String
  , collectionCount :: Availability Word64
  , collectionMilliseconds :: Availability Word64
    -- ^ Approximate elapsed collection time, not pause time or CPU time.
  } deriving (Eq, Show)

-- | Enumerate collectors. Individual optional/invalidated counters retain
-- their own status. An empty available list is different from no JVM support.
collectors :: IO (Availability [CollectorStats])
collectors = do
  count <- query 300 0 0 :: IO (Availability Int64)
  case count of
    Available size -> Available <$> traverse readCollector [0 .. size - 1]
    Unsupported -> pure Unsupported
    Disabled -> pure Disabled
    Denied -> pure Denied
    Unavailable -> pure Unavailable
  where
    readCollector index = CollectorStats
      <$> queryText 301 index <*> queryWord64 302 index 0 <*> queryWord64 303 index 0
