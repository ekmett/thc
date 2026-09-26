-- SPDX-FileCopyrightText: 2026 Edward Kmett
-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
{-# LANGUAGE ForeignFunctionInterface, ScopedTypeVariables #-}
module Main (main) where

import Control.Exception
  ( AsyncException(ThreadKilled), Exception, MaskingState(..), getMaskingState
  , mask_, throwIO, try )
import Control.Monad (unless)
import Data.Word (Word8)
import Foreign.C.Types (CInt(..), CLLong(..))
import Foreign.Ptr (Ptr, nullPtr)
import qualified THC
import qualified THC.GC as GC
import qualified THC.Internal.JIT as JIT
import qualified THC.Memory as Memory
import THC.Runtime
import qualified THC.Thread as Thread
import qualified THC.Trace as Trace

data ProbeFailure = ProbeFailure deriving (Eq, Show)
instance Exception ProbeFailure

-- Negative tests of the private native shim, not additional public APIs.
foreign import ccall unsafe "thc_runtime_v1_query"
  rawQuery :: CInt -> CLLong -> CLLong -> IO CLLong
foreign import ccall unsafe "thc_runtime_v1_control"
  rawControl :: CInt -> CLLong -> IO CLLong
foreign import ccall unsafe "thc_runtime_v1_trace"
  rawTrace :: CInt -> CLLong -> Ptr Word8 -> CLLong -> IO CLLong

check :: Bool -> String -> IO ()
check condition message = unless condition (fail message)

main :: IO ()
main = do
  malformed <- sequence
    [rawQuery 999 0 0, rawQuery 0 1 0, rawQuery 301 (-1) 0,
     rawQuery 5 0 (-2), rawControl 400 2, rawControl 500 4,
     rawTrace 4 0 nullPtr 0, rawTrace 0 0 nullPtr 1, rawTrace 2 0 nullPtr 0]
  check (all (== -5) malformed) "malformed private native ABI is diagnostic, not an available value"
  info <- runtimeInfo
  check (runtimeKind info == Available NativeGHC) "native runtime identity"
  check (executingBackend info == Available NativeBackend) "native backend identity"
  check (case runtimeVersion info of Available value -> not (null value); _ -> False)
    "native compiler version"
  check (jvmName info == Unsupported && jvmVersion info == Unsupported) "native JVM identity is unsupported"
  capabilities <- runtimeCapabilities
  check (capabilities == RuntimeCapabilities Unsupported Unsupported Unsupported) "native context permissions are unsupported"
  thread <- Thread.currentThreadInfo
  check (thread == Thread.ThreadInfo (Available Thread.NativeHaskellThread)
    Unsupported Unsupported Unsupported Unsupported) "native thread kind and unavailable JVM observations"
  accounting <- Thread.currentThreadAccounting
  check (accounting == Thread.ThreadAccounting Unsupported Unsupported Unsupported) "no fabricated native JVM accounting"
  coordinates <- Thread.eligibleCPUs
  check (coordinates == Unsupported) "native affinity coordinates unsupported"
  affinity <- THC.cpuAffinitySupport
  applied <- THC.affinityApplied
  check (affinity == THC.NoCpuAffinity && not applied) "facade preserves native affinity compatibility"
  heap <- Memory.heapUsage
  nonHeap <- Memory.nonHeapUsage
  let absentMemory = Memory.MemoryUsage Unsupported Unsupported Unsupported Unsupported
  check (heap == absentMemory && nonHeap == absentMemory) "no fabricated JVM heap values"
  native <- Memory.nativeAllocationUsage
  check (native == Memory.NativeAllocationUsage Unsupported Unsupported) "native GHC is not the THC allocation registry"
  collectors <- GC.collectors
  check (collectors == Unsupported) "native GC is not the JVM collector list"
  enabled <- JIT.jitTelemetryEnabled
  changed <- JIT.setJitTelemetryEnabled True
  snapshot <- JIT.jitSnapshot
  check (enabled == Unsupported && changed == Unsupported) "native JIT monitoring unsupported"
  check (snapshot == JIT.JitSnapshot Unsupported Unsupported Unsupported Unsupported Unsupported Unsupported)
    "no fabricated native JIT counters"
  sinks <- Trace.supportedTraceSinks
  sink <- Trace.getTraceSink
  check (sinks == Unsupported && sink == Unsupported) "native JVM trace sink unavailable"
  mapM_ (\choice -> Trace.setTraceSink choice >>= \result -> check (result == Unsupported) "native trace control unsupported")
    [Trace.TraceOff, Trace.TraceStderr, Trace.TraceJFR, Trace.TraceStderrAndJFR]
  mapM_ (\label -> Trace.traceEvent label >>= \result -> check (result == Unsupported) "native trace event unsupported")
    ["", "ASCII", "lambda \x3bb, astral \x1f680, NUL \0 end", "\xd800"]
  before <- getMaskingState
  result <- Trace.withSpan "outer" (Trace.withSpan "inner" getMaskingState)
  after <- getMaskingState
  check (result == before && after == before) "span preserves caller masking"
  masked <- mask_ (Trace.withSpan "masked" getMaskingState)
  check (masked == MaskedInterruptible) "span preserves inherited mask"
  normal <- Trace.withSpan "value" (pure (73 :: Int))
  check (normal == 73) "unsupported tracing must still return action result"
  failed <- try (Trace.withSpan "failure" (throwIO ProbeFailure)) :: IO (Either ProbeFailure ())
  check (failed == Left ProbeFailure) "span preserves original action exception"
  interrupted <- try (Trace.withSpan "interruption" (throwIO ThreadKilled)) :: IO (Either AsyncException ())
  check (interrupted == Left ThreadKilled) "span does not swallow async exceptions"
  oversized <- try (Trace.traceEvent (replicate 1048577 'x')) :: IO (Either IOError (Availability ()))
  check (case oversized of Left _ -> True; Right _ -> False) "UTF-8 payload limit checked before allocation"
  check (fmap (+ (1 :: Int)) Disabled == Disabled && fmap (+ (1 :: Int)) Denied == Denied
    && fmap (+ (1 :: Int)) Unavailable == Unavailable && fmap (+ (1 :: Int)) (Available 0) == Available 1)
    "availability statuses remain distinct from successful zero"
  putStrLn "runtime-services-api: native compatibility, statuses, Unicode bounds, tracing masks/results/exceptions passed"
