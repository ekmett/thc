-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
module Main (main) where

import Control.Monad (unless)
import qualified THC.GC as GC
import qualified THC.Internal.JIT as JIT
import qualified THC.Memory as Memory
import THC.Runtime
import qualified THC.Thread as Thread
import qualified THC.Trace as Trace

check :: String -> Bool -> IO ()
check label condition = unless condition (fail ("runtime service smoke: " ++ label))

main :: IO ()
main = do
  info <- runtimeInfo
  guest <- case runtimeKind info of
    Available NativeGHC -> pure False
    Available TruffleHaskell -> pure True
    _ -> fail "runtime identity unavailable"
  check "backend identity" $ case executingBackend info of
    Available NativeBackend -> not guest
    Available ASTBackend -> guest
    Available BytecodeBackend -> guest
    _ -> False
  check "runtime version" $ case runtimeVersion info of
    Available version -> not (null version)
    _ -> False
  capabilities <- runtimeCapabilities
  check "CPU capacity scope" $ case cpuCapacity capabilities of
    Available count -> guest && count > 0
    Unsupported -> not guest
    _ -> False
  thread <- Thread.currentThreadInfo
  check "thread kind" $ case Thread.threadKind thread of
    Available Thread.NativeHaskellThread -> not guest
    Available Thread.PlatformThread -> guest
    Available Thread.VirtualThread -> guest
    _ -> False
  accounting <- Thread.currentThreadAccounting
  Thread.threadCpuNanoseconds accounting `seq` Thread.threadAllocatedBytes accounting `seq` pure ()
  coordinates <- Thread.eligibleCPUs
  coordinates `seq` pure ()
  heap <- Memory.heapUsage
  nonHeap <- Memory.nonHeapUsage
  check "JVM heap accounting" $ case Memory.usedBytes heap of
    Available _ -> guest
    Unsupported -> not guest
    Denied -> guest
    _ -> False
  Memory.maximumBytes nonHeap `seq` pure ()
  native <- Memory.nativeAllocationUsage
  check "context native allocation accounting" $ case Memory.nativeRequestedBytes native of
    Available _ -> guest
    Unsupported -> not guest
    _ -> False
  collectors <- GC.collectors
  check "collector availability" $ case collectors of
    Available values -> guest && all hasName values
    Unsupported -> not guest
    Denied -> guest
    _ -> False
  wasEnabled <- JIT.jitTelemetryEnabled
  changed <- JIT.setJitTelemetryEnabled True
  check "JIT explicit opt-in" (changed == if guest then Available () else Unsupported)
  enabled <- JIT.jitTelemetryEnabled
  check "JIT query after opt-in" (enabled == if guest then Available True else Unsupported)
  snapshot <- JIT.jitSnapshot
  check "JIT counter availability" $ case JIT.compilationsStarted snapshot of
    Available _ -> guest
    Unsupported -> not guest
    _ -> False
  _ <- JIT.setJitTelemetryEnabled (wasEnabled == Available True)
  previousSink <- Trace.getTraceSink
  sink <- Trace.setTraceSink Trace.TraceStderr
  check "trace sink selection" (sink == if guest then Available () else Unsupported)
  event <- Trace.traceEvent "Haskell API \x3bb \x1f680 \0 complete"
  check "trace event" (event == if guest then Available () else Unsupported)
  value <- Trace.withSpan "public Haskell span" $ Trace.withSpan "nested" (pure (73 :: Int))
  check "span action result" (value == 73)
  _ <- Trace.setTraceSink (case previousSink of Available selected -> selected; _ -> Trace.TraceOff)
  pure ()
  where
    hasName collector = case GC.collectorName collector of
      Available name -> not (null name)
      Unavailable -> True
      Denied -> True
      _ -> False
