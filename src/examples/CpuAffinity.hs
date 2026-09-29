-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

-- |
-- Module      : CpuAffinity
-- Copyright   : (C) 2026 Edward Kmett
-- License     : UPL-1.0 AND BSD-3-Clause
-- Maintainer  : Edward Kmett <ekmett@gmail.com>
-- Stability   : experimental
-- Portability : THC runtime API with native GHC fallbacks
--
-- Query affinity support and exercise the public affinity-aware fork API.
module CpuAffinity (main, supportQuery, acceptanceQuery) where

import Control.Concurrent (newEmptyMVar, putMVar, takeMVar)
import THC

-- Small genuine IO () audit roots, without the example's printing closure.
supportQuery :: IO ()
supportQuery = cpuAffinitySupport >>= \support -> support `seq` pure ()

acceptanceQuery :: IO ()
acceptanceQuery = affinityApplied >>= \accepted -> accepted `seq` pure ()

main :: IO ()
main = do
  support <- cpuAffinitySupport
  result <- newEmptyMVar
  _ <- forkOnWithAffinity 0 (putMVar result)
  accepted <- takeMVar result
  print (support, accepted)
