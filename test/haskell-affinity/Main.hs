-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Concurrent (myThreadId, newEmptyMVar, putMVar, takeMVar)
import Control.Exception (getMaskingState, mask_, MaskingState(..))
import Control.Monad (unless)
import System.Timeout (timeout)
import THC

main :: IO ()
main = do
  support <- cpuAffinitySupport
  applied <- affinityApplied
  unless (support == NoCpuAffinity && not applied)
    (fail "Native GHC shim must not claim forkOn CPU affinity")
  parent <- myThreadId
  result <- newEmptyMVar
  child <- mask_ $ forkOnWithAffinity (-1) $ \accepted -> do
    identity <- myThreadId
    masking <- getMaskingState
    queried <- affinityApplied
    putMVar result (accepted, queried, masking, identity)
  observed <- timeout 5000000 (takeMVar result)
  case observed of
    Just (False, False, MaskedInterruptible, identity)
      | identity == child && identity /= parent -> pure ()
    _ -> fail "Affinity callback did not run with ordinary forkOn masking/thread behavior"
  -- The callback closure is entered by the child, never eagerly on its parent.
  lazyResult <- newEmptyMVar
  _ <- forkOnWithAffinity 0 (\accepted -> putMVar lazyResult (not accepted))
  completed <- timeout 5000000 (takeMVar lazyResult)
  unless (completed == Just True) (fail "Unavailable affinity suppressed the callback")
  putStrLn "cpu-affinity-api: native support/query, child identity, inherited mask and fallback callback passed"
