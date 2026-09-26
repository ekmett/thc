-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module THC.RuntimeServices (main, runtimeSmoke, memorySmoke, threadSmoke, traceSmoke) where

import qualified THC.GC as GC
import qualified THC.Memory as Memory
import THC.Runtime
import qualified THC.Thread as Thread
import qualified THC.Trace as Trace

-- Genuine public API roots, usable without bringing printing into the closure.
runtimeSmoke :: IO ()
runtimeSmoke = runtimeInfo >>= \info -> runtimeKind info `seq` pure ()

memorySmoke :: IO ()
memorySmoke = Memory.heapUsage >>= \usage -> Memory.usedBytes usage `seq` pure ()

threadSmoke :: IO ()
threadSmoke = Thread.currentThreadInfo >>= \info -> Thread.threadKind info `seq` pure ()

traceSmoke :: IO ()
traceSmoke = Trace.withSpan "runtime smoke" (Trace.traceEvent "hello \x3bb \x1f680" >> pure ())

main :: IO ()
main = do
  print =<< runtimeInfo
  print =<< runtimeCapabilities
  print =<< Thread.currentThreadInfo
  print =<< Thread.currentThreadAccounting
  print =<< Thread.eligibleCPUs
  print =<< Memory.heapUsage
  print =<< Memory.nonHeapUsage
  print =<< Memory.nativeAllocationUsage
  print =<< GC.collectors
  print =<< Trace.setTraceSink Trace.TraceStderr
  Trace.withSpan "example" $ print =<< Trace.traceEvent "runtime services"
