# Haskell runtime services

Depend on `thc:runtime`. Its only Haskell dependency is `base`, and the same
source compiles and links under native GHC. The runtime detects where it is
executing through exact versioned foreign calls, not CPP or environment guesses.

```haskell
import THC.Runtime
import qualified THC.Memory as Memory
import qualified THC.Trace as Trace

main :: IO ()
main = do
  print =<< runtimeInfo
  print =<< Memory.heapUsage
  print =<< Trace.setTraceSink Trace.TraceStderr
  Trace.withSpan "application" $ do
    _ <- Trace.traceEvent "started"
    pure ()
```

The [complete example](../examples/THC/RuntimeServices.hs) also shows thread,
affinity, allocation and collector queries. The `THC` facade re-exports runtime
identity/capabilities and the original [affinity API](cpu-affinity-api.md), but
does not re-export hazardous internal diagnostics.

## Availability, scope and safety

Every optional value uses `Availability a`:

| Result | Meaning |
| --- | --- |
| `Available value` | A real value, including a genuine zero. |
| `Unsupported` | The runtime/provider does not implement the service. |
| `Disabled` | Supported instrumentation or its sink is off. |
| `Denied` | Context permissions or host security policy forbid the operation. |
| `Unavailable` | No value can currently be obtained, including unknown JVM counters. |

Do not convert all absent results to zero. Native GHC reports its identity,
compiler version and native-Haskell thread kind, but reports `Unsupported` for
JVM services; it does not reinterpret GHC RTS statistics as JVM statistics.
The existing affinity compatibility functions preserve their original
`NoCpuAffinity`/`False` fallback.

Queries never implicitly enable JVM-wide accounting, trigger GC, start a JFR
recording, force compilation, or resize thread pools. The record-valued APIs
sample their fields independently: they are **not atomic snapshots**, and
cross-field consistency is not guaranteed under concurrent activity.

The public facade is explicitly `Safe`. `THC.Runtime`, `THC.Thread`, `THC.Memory`,
`THC.GC` and `THC.Trace` are explicitly `Trustworthy`: their trusted boundary
uses fixed typed selectors, validates ABI results and bounds trace buffers; it
does not expose raw selectors, pointers, Java handles, carrier pinning or Java
interruption. A `Safe` client can import these wrappers. Safe Haskell is not a
resource/permissions sandbox: these are IO APIs and can observe JVM-wide metrics
or write diagnostics where their documented scope permits.

`THC.Internal.JIT` is explicitly **`Unsafe`**. `.Internal` denotes an unstable,
potentially hazardous interface, not just a promise that names may change.
The private raw ABI module is also `Unsafe` and is not exposed by the package.
Do not circumvent these boundaries by re-exporting internal controls from a
`Trustworthy` application module without performing your own safety audit.

## `THC.Runtime`

`runtimeInfo :: IO RuntimeInfo` reports `NativeGHC` or `TruffleHaskell`, the
actual executing `NativeBackend`/`ASTBackend`/`BytecodeBackend`, runtime version,
JVM version and JVM name. Version strings are informational, not a stable feature
negotiation scheme.

`runtimeCapabilities :: IO RuntimeCapabilities` reports native-access and
thread-creation permissions for this THC context, and its initial CPU capacity.
CPU capacity is not the number of live Haskell threads, does not shrink when a
child is pinned, and is not dynamic GHC `setNumCapabilities` support.

## `THC.Thread`

`currentThreadInfo :: IO ThreadInfo` reports:

- `NativeHaskellThread`, `PlatformThread` or `VirtualThread`;
- the current logical capability and whether it was requested as locked;
- affinity-provider support, with unavailable/denied reasons;
- whether this guest fork's initial native affinity request was accepted.

The lock bit is not evidence of a successful physical CPU pin. Acceptance is
not a continuing guarantee against OS policy changes and does not establish
bound foreign-thread TLS. The old `cpuAffinitySupport`, `affinityApplied` and
`forkOnWithAffinity` remain available in both `THC.Thread` and `THC`.

`currentThreadAccounting :: IO ThreadAccounting` reports cumulative CPU and
user nanoseconds and allocated heap bytes for the current JVM platform thread.
These include host/runtime work on that thread, not just this THC context or
Haskell action, and begin when the corresponding JVM accounting begins. They
are not live heap usage, allocation budgets or guest-only profiler samples.
Unsupported virtual-thread counters and disabled accounting are explicit; a
query does not turn monitoring on.

The older guest allocation-counter implementation separately enables JVM
allocation accounting on first guest entry when supported. This batch does not
change that existing policy; these new read-only accounting queries never call
the management bean's enable/disable setters themselves.

`eligibleCPUs :: IO (Availability [CpuCoordinate])` returns the context's
initial CPU eligibility in dense logical-capability order, capped by JVM CPU
capacity. Linux uses group zero and potentially sparse OS CPU IDs. Windows uses
processor group and processor number, not CPU Set IDs. This is an initial
eligibility snapshot, not the current calling thread's affinity mask.

Both guest fork forms currently request **platform threads**, not virtual/green
threads. Native affinity rejects virtual-thread callers rather than pinning a
shared carrier. This costs a platform thread per guest fork. See the detailed
[scheduling contract](thread-scheduling.md) for OS support and compiler-worker
affinity reset limitations.

## `THC.Memory`

`heapUsage` and `nonHeapUsage` return JVM-wide `MemoryUsage`: used, committed,
maximum and initial bytes. JVM unknown maximum/initial sizes are `Unavailable`.
These values describe the JVM, not one guest context, and are not process RSS.

`nativeAllocationUsage` returns **context-owned libc requested live bytes and
allocation count**. The accounting includes a freed allocation while outstanding
borrowers still defer its release. It excludes allocator overhead, pinned-array
storage, other native providers and unrelated Sulong/JVM native allocations.
An empty context registry genuinely returns available zero without requiring
native-access permission; native GHC has no such THC registry and returns
`Unsupported`.

## `THC.GC`

`collectors :: IO (Availability [CollectorStats])` reports collector names,
cumulative collection counts, and approximate cumulative elapsed collection
**milliseconds** for the whole JVM. Collection elapsed time is neither pause
time nor collector CPU time. Unknown/invalidated counters remain `Unavailable`.
The JVM collector list is consulted per query; records are not a coherent
stop-the-world snapshot. No collection is requested.

## `THC.Trace`

`getTraceSink`, `supportedTraceSinks` and `setTraceSink` use typed `TraceOff`,
`TraceStderr`, `TraceJFR` and `TraceStderrAndJFR` values. Selection is context-local.
JFR availability does not imply an active recording, and selecting its sink
never starts a process-wide recording. With only JFR selected and no enabled
recording consumer, emitting/beginning a span returns `Disabled`. With both
sinks selected, a successful stderr write suffices even if JFR is disabled.

`traceEvent :: String -> IO (Availability ())` emits a structured instant event.
Labels are exact UTF-8 data, including embedded NUL, limited to 1 MiB of encoded
bytes. Invalid Unicode surrogate `Char`s are replaced with U+FFFD. Oversized
labels raise an IO error before native-buffer allocation; they are not silently
truncated. Input is data, never code. Off means no diagnostic output.

`withSpan :: String -> IO a -> IO a` creates an opaque, context-owned span token
and attempts a success/failure end after the action. Begin/end are masked, while
the body sees the caller's original masking state. Disabled or unsupported
tracing still runs the action. Cleanup cannot replace the body's exception;
synchronous cleanup failures cannot replace its successful value. Asynchronous
exceptions on successful cleanup still propagate. The span covers the IO action,
not later evaluation of a returned lazy value. Labels are validated before
entering the body, so malformed/oversized input can still prevent execution.

The end uses the current sink and the original name. Disabling tracing during
the body retires the token silently at completion. Changing the sink during the
body can split a span's begin and end across sinks. See the implementation tests
for context ownership, invalid UTF-8 and sink-change controls.

## `THC.Internal.JIT` — unsafe, unstable diagnostics

Import this module explicitly; it is intentionally rejected by Safe Haskell.
The Graal integration is version-pinned and enabling callbacks adds overhead and
can perturb compilation timing. It exposes no raw Java target handles, does not
force compilation and does not claim that a particular Haskell entry compiled.

`jitTelemetryEnabled` observes context-local telemetry and
`setJitTelemetryEnabled :: Bool -> IO (Availability ())` explicitly toggles it.
It starts off. `jitSnapshot :: IO JitSnapshot` reads compilation queued, started,
succeeded and failed callbacks, invalidations and deoptimizations attributed to
this context. Counter queries are `Disabled` while off. Unsupported runtime
integrations remain explicit rather than producing an all-zero success.

Counters include all compilation tiers and repeated compilations, observe only
callbacks while enabled, and survive disable/re-enable. A start may happen while
disabled and its completion while enabled, so starts and completions need not
match. They are not resident code size or the number of currently compiled
targets. Fields are independently sampled. Counts saturate at `2^63 - 1`.
Invalidations specifically count Truffle `onCompilationInvalidated` callbacks;
they are not all machine-code retirement. A deoptimization can occur without
that invalidation callback; observed code retirement can occur with neither an
invalidation nor deoptimization callback. These are not exhaustive JVM-wide
deopt counts, and zero does not establish code liveness or absence of deopts.

## Native checks and ABI

```sh
cabal test runtime-services-api cpu-affinity-api -fdevelopment
bash test/haskell-runtime/check-safe-haskell.sh
ghc --make -XHaskell2010 -threaded -Wall -Werror -iruntime \
  examples/THC/RuntimeServices.hs runtime/cpu-affinity.c runtime/runtime-services.c \
  -main-is THC.RuntimeServices
```

The Safe Haskell check accepts imports of all stable modules and requires an
actual unsafe-import rejection for `THC.Internal.JIT`. Native tests check honest
fallbacks and mask/result/exception preservation, not pretend JVM measurements.

The [multi-package smoke program](../test/fixtures/run-runtime-services/Main.hs)
depends on the real `thc:runtime` library and checks runtime-specific invariants
across all six modules, including explicit JIT opt-in and nested Unicode spans.
It does not compare variable JVM counters against native-GHC zeroes. Its
`cabal.project` uses relative paths; it can be passed to `thc run` with complete
installed Core and the configured GHC source provider described in the
[driver guide](driver.md). A successful native run alone does not establish
that its entire original Core closure is accepted by THC.

The private versioned ABI consists of three exact ordinary `ccall unsafe`
declarations: `thc_runtime_v1_query`, `thc_runtime_v1_control` and
`thc_runtime_v1_trace`. Both loaders reserve these before package-C dispatch and
validate their real GHC descriptor shapes. Successful scalar results are
nonnegative; `-1/-2/-3/-4` represent the four absence statuses. Text queries
address Unicode codepoints. Raw selectors and trace pointers are deliberately
not part of the stable Haskell interface.
