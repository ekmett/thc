-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE Safe #-}
module SafeClient (safeClient) where

import THC (runtimeInfo)
import THC.Runtime (RuntimeInfo)
import qualified THC.Thread as Thread
import qualified THC.Memory as Memory
import qualified THC.GC as GC
import qualified THC.Trace as Trace

safeClient :: IO RuntimeInfo
safeClient = Trace.withSpan "safe client" $ do
  _ <- Thread.currentThreadInfo
  _ <- Memory.heapUsage
  _ <- GC.collectors
  runtimeInfo
