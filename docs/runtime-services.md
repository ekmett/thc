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

The [complete example](../src/examples/RuntimeServices.hs) also shows thread,
affinity, allocation and collector queries. The `THC` facade re-exports runtime
identity/capabilities and the original [affinity API](cpu-affinity-api.md), but
does not re-export hazardous internal diagnostics.

## Availability, scope and safety

Every optional value uses `Available a`:

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
child is pinned, and is independent of the logical count changed by original
`setNumCapabilities`. The count controls Loom HEC routing without resizing
JVM/compiler/GC pools; see
[RTS capabilities](rts-event-capabilities.md).

## `THC.Thread`

`currentThreadInfo :: IO ThreadInfo` reports:

- `NativeHaskellThread`, `PlatformThread` or `VirtualThread`;
- the current logical capability and whether it was requested as locked;
- affinity-provider support, with unavailable/denied reasons;
- whether the platform fork's native affinity request, or its locked Loom HEC's pin, was accepted.

The lock bit is not evidence of a successful physical CPU pin. Acceptance is
not a continuing guarantee against OS policy changes and does not establish
bound foreign-thread TLS. `cpuAffinitySupport`, `affinityApplied` and
`forkOnWithAffinity` remain available in both `THC.Thread` and `THC`.

`currentThreadAccounting :: IO ThreadAccounting` reports cumulative CPU and
user nanoseconds and allocated heap bytes for the current JVM platform thread.
These include host/runtime work on that thread, not just this THC context or
Haskell action, and begin when the corresponding JVM accounting begins. They
are not live heap usage, allocation budgets or guest-only profiler samples.
Unsupported virtual-thread counters and disabled accounting are explicit; a
query does not turn monitoring on.

The guest allocation-counter implementation separately enables JVM allocation
accounting on first guest entry when supported. These read-only queries never
change the accounting setting.

`eligibleCPUs :: IO (Available [CpuCoordinate])` returns the context's
initial CPU eligibility in dense logical-capability order, capped by JVM CPU
capacity. Linux uses group zero and potentially sparse OS CPU IDs. Windows uses
processor group and processor number, not CPU Set IDs. This is an initial
eligibility snapshot, not the current calling thread's affinity mask.

Guest forks use platform threads by default. Opt-in `thc.ThreadHosting=loom`
uses virtual threads routed through exclusive logical HEC workers; native affinity
applies to those workers. Native affinity still rejects direct virtual-thread
callers. See the [scheduling contract](thread-scheduling.md) for setup, foreign
transition limits and compiler-worker affinity restrictions.

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

`collectors :: IO (Available [CollectorStats])` reports collector names,
cumulative collection counts, and approximate cumulative elapsed collection
**milliseconds** for the whole JVM. Collection elapsed time is neither pause
time nor collector CPU time. Unknown/invalidated counters remain `Unavailable`.
The JVM collector list is consulted per query; records are not a coherent
stop-the-world snapshot. No collection is requested.

## `THC.Trace`

`getTraceSink`, `supportedTraceSinks` and `setTraceSink` use typed `TraceOff`,
`TraceStderr`, `TraceJFR` and `TraceStderrAndJFR` values. Selection is context-local
and starts at `TraceOff`. The same sink selects GHC's original `traceEvent#`,
`traceMarker#` and `traceBinaryEvent#`; see [their payload contract](hints-and-tracing.md).
Enabling tracing invalidates the initial disabled assumption, so already compiled
guest code observes the change. Disabling silences subsequent emissions; re-enabling
uses the newly selected sink. This is a behavior guarantee, not a measured claim
about the cost of compiled disabled code.
JFR availability does not imply an active recording, and selecting its sink
never starts a process-wide recording. With only JFR selected and no enabled
recording consumer, emitting/beginning a span returns `Disabled`. With both
sinks selected, a successful stderr write suffices even if JFR is disabled.

`traceEvent :: String -> IO (Available ())` emits a structured instant event.
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
`setJitTelemetryEnabled :: Bool -> IO (Available ())` explicitly toggles it.
It starts off. `jitSnapshot :: IO JitSnapshot` reads compilation queued, started,
succeeded and failed callbacks, invalidations and deoptimizations attributed to
this context. Counter queries are `Disabled` while off. Unsupported runtime
integrations remain explicit rather than producing an all-zero success.

Ordinary execution roots and their clones retain an opaque context-ownership
token. Compiler callbacks use that token, not a compiler thread's current context
or the shared Language object. Generated continuations and OSR wrappers are
attributed through their actual source root, including nested wrappers.
Reusable code and cached load factories have no
single context owner, so their compilation events are not charged to any context.
This does not suppress per-program guest-entry or thunk-evaluation metrics.

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

## Run the example

The [runtime-services example](../t/fixtures/run-runtime-services/Main.hs)
depends on the real `thc:runtime` library. With complete installed Core and the
matching GHC source checkout configured, run from the repository root:

```sh
THC_BACKEND=bytecode thc run runtime-services-smoke:exe:completed \
  --project-dir t/fixtures/run-runtime-services \
  --thc-root "$PWD" --runtime "$PWD/build/install/thc/bin/thc" \
  --dist-dir "$PWD/build/runtime-services-smoke" \
  --installed-core required --ghc-source "$THC_GHC_SOURCE"
```

See the [driver guide](driver.md) for dependency setup. Native GHC reports
unsupported JVM services explicitly; values such as heap usage and live thread
counts naturally vary between runs.

## Native compatibility shims in Cabal projects

The runtime library explicitly declares `x-thc-runtime-shim: v1`. Its C files
are native-GHC fallbacks, not providers to run through Sulong. The project driver
retains Cabal's actual C compiler receipts, source hashes, object hashes and
component/unit identity, then hydrates the real exported GHC interfaces. Every
typed foreign declaration and actual Core foreign call must match one of the
five exact reserved runtime/affinity signatures, including the trace pointer
argument. Same-unit inlining may move a call between modules; it must still
have its verified declaration in that component.

This is not a package-name or symbol-prefix exemption. Non-runtime imports,
wrong signatures/conventions/safety/owners, foreign data, dynamic calls,
native export/stub obligations and unverified import provenance are rejected.
The explicit profile says that these native products are compatibility
fallbacks: they are recorded but not linked or initialized in the guest.
It does not expand the separate generic scalar-C profile to arbitrary pointer
FFI or admit unrelated Haskell foreign calls. Producers must not use this marker
for required guest-side native initializers or other native behavior.
