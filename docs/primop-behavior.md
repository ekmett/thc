<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Primop behavior: known differences and limitations

THC has an implementation for every primop in its pinned GHC 9.14.1 inventory.
That is **implementation coverage, not a claim of identical GHC RTS behavior**.
This page names the known differences that matter to callers, including cases
that reject, retain resources, or deliberately do less work. Unless stated
otherwise, a row applies to both the AST and bytecode backends, interpreted and
compiled. Exact primop spellings are searchable below; names sharing a row have
the same stated restriction.

This is a current behavioral reference, not a historical test report. The
[generated checklist](primops.md) is the complete inventory; an operation absent
here has no specific infelicity recorded here, not a proof of equivalence on
every input. JVM/Sulong pointers, managed storage, typed vector representations,
and ordinary GHC input preconditions are not themselves missing functionality.
The tables distinguish unsupported behavior from intentional target choices
and performance-only hints. Linked implementation guides supply detail and
test commands.

## Sparks and thread scheduling

| Primop | Current behavior and consequence |
| --- | --- |
| `par#` | Discards the speculative evaluation hint without forcing its argument; returns `1`. No parallel evaluation is started. |
| `spark#` | Discards the hint and returns the identical, unforced payload. |
| `numSparks#` | Always returns `0`; there is no spark queue. |
| `getSpark#` | Always returns failure flag `0` and the pinned GHC boxed `False` filler; no work is dequeued. |
| `fork#` | Creates a real Java platform thread rather than a lightweight GHC scheduler thread. Thread creation must be allowed by the embedding. Attempts to clear inherited CPU affinity to the context baseline. Ordinary AST children do not support externally delivered asynchronous exceptions; see `killThread#` below. |
| `forkOn#` | Same thread and AST restrictions as `fork#`. Chooses a dense logical capability modulo the context's snapshotted CPU capacity. Native affinity is **best effort**: Linux requests a per-thread pin; Windows requests advisory CPU Sets and declines unresolved multi-group topology; macOS, unavailable native access, or a rejected request run unpinned without failing the fork. |
| `threadStatus#` | Capability is a context-local assignment, not a measurement of the currently executing physical CPU. The lock flag records a `forkOn#` request, **not successful OS affinity**. Ordinary threads share logical capabilities. |
| `listThreads#` | Lists context-owned guest identities, not every JVM thread. Retained completed identities and host carriers between guest invocations can appear; ordering is unspecified. |
| `isCurrentThreadBound#` | Always returns `0`. Platform threads and CPU affinity do not provide GHC's bound-thread/foreign-TLS contract. |
| `setThreadAllocationCounter#` | Accounts JVM heap bytes during outer guest-entry extents, including runtime bookkeeping and excluding native/Sulong allocations and host work between entries. Requires JVM thread-allocation accounting support. Does **not** enforce allocation limits. |
| `setOtherThreadAllocationCounter#` | Same accounting and missing allocation-limit enforcement, for the selected context-owned thread. |

Discarding sparks is a deliberate hint policy, not a claim of parallel speedup.
Real forks are independent of that policy. CPU pins can be inherited by native
or JVM helper threads. The version-pinned Graal compiler-worker listener resets
recognized workers before compilation, including replacement workers; helpers
created earlier during initialization and unrecognized workers are not covered.
Use the public [THC affinity API](cpu-affinity-api.md) to observe native request
acceptance; `threadStatus#` cannot supply that information.

**Virtual-thread safety:** guest forks explicitly use
[`newTruffleThreadBuilder(...).virtual(false)`](../src/main/kotlin/thc/runtime/GuestThreadOps.kt).
They do not migrate between Java virtual-thread carriers. Both the
[Linux](../src/main/kotlin/thc/runtime/CpuAffinity.kt) and
[Windows](../src/main/kotlin/thc/runtime/WindowsCpuAffinity.kt) affinity paths
refuse native affinity changes on virtual threads, including when an embedding
enters from one. Do not remove those guards or switch guest forks to virtual
threads while retaining carrier-local pinning: a pin could otherwise affect
unrelated work on a shared carrier. This safeguard does not eliminate the
helper-inheritance caveat above.

Details: [scheduling and affinity](thread-scheduling.md),
[thread status](thread-status.md), [thread inventory](thread-inventory.md).

## Exceptions, blocking and transactions

| Primop | Current behavior and consequence |
| --- | --- |
| `catch#` | Supports lifted boxed action results, but rejects valid single-word unboxed results such as `Int#`, `Word#` or `Addr#`. This is separate from the asynchronous-delivery restrictions below. |
| `raiseIO#` | Accepts only lifted boxed exception payloads and lifted boxed result positions. The GHC signature also admits boxed-unlifted payloads and representation-polymorphic bottom results. |
| `maskAsyncExceptions#` | Implements interruptible masking/restoration, but action results must be lifted boxed; valid single-word unboxed results reject. |
| `maskUninterruptible#` | Implements uninterruptible masking/restoration, with the same lifted-boxed result restriction. |
| `unmaskAsyncExceptions#` | Implements unmasking/restoration, with the same lifted-boxed result restriction. |
| `killThread#` | Ordinary AST callers support self-delivery but reject external sends before enqueueing. Ordinary AST children also reject external delivery from other backends. General resumable delivery uses bytecode saved guest continuations; the restricted captured-AST route is not general AST support. Arbitrary Java/native foreign frames do not gain resumable interruption. Sends to host carriers outside guest invocations are no-ops, not messages queued for a later unrelated host call. |
| `takeMVar#` | Blocking transfers and supported interruption work, but no GC-driven `BlockedIndefinitelyOnMVar` detection. A wait with no future producer needs supported interruption or embedding cancellation to end. |
| `putMVar#` | Same missing deadlock exception for a blocked put; FIFO handoff and cancellation-before-commit are implemented. |
| `readMVar#` | Same missing deadlock exception for a blocked read; reader broadcast is implemented. |
| `atomically#` | Synchronous transactions work. Transaction frames cannot travel with saved asynchronous/delimited continuations: resumable bytecode async/checkpoint mode and captured AST lowering reject them. |
| `retry#` | Same transaction-continuation restriction. No GC-driven `BlockedIndefinitelyOnSTM`: retries with no possible future wakeup, including empty read sets, wait until cancellation/disposal. |
| `catchRetry#` | Synchronous alternative/rollback behavior works; transaction-continuation restriction above applies. |
| `catchSTM#` | Synchronous catch/rollback behavior works; transaction-continuation restriction above applies. |
| `readTVar#` | Validated transactional reads work; transaction-continuation restriction above applies. |
| `writeTVar#` | Buffered writes and atomic commit work; transaction-continuation restriction above applies. |
| `raiseDivZero#` | Raises the original GHC exception closure for scalar and concrete tuple bottom results; direct vector and unboxed-sum results are rejected. |
| `raiseOverflow#` | Same result-shape restriction, with the original overflow exception. |
| `raiseUnderflow#` | Same result-shape restriction, with the original underflow exception. |

Do not apply the transaction-frame restriction to `newTVar#` or `readTVarIO#`:
they do not require such a frame. The absence of GC-driven deadlock exceptions
is separate from functioning MVar handoff and STM read-set wakeups.

Details: [asynchronous exceptions](async-exceptions.md), [MVars](managed-mvars.md),
[STM](stm.md), [arithmetic exception implementation](../src/main/kotlin/thc/runtime/GuestExceptions.kt).

## Weak pointers and finalization

| Primop | Current behavior and consequence |
| --- | --- |
| `mkWeak#` | Strongly retains its lazy key, value and Haskell action until explicit finalization or context disposal. **No automatic weak-key/ephemeron collection**; otherwise unreachable resources can remain for the context lifetime. Dropping the `Weak#` does not remove its registration. |
| `mkWeakNoFinalizer#` | Same retained key/value behavior, without a Haskell finalizer. |
| `deRefWeak#` | Liveness changes through explicit finalization/disposal, not GC discovering a dead key. An unfinalized registration still returns its retained value. |
| `finalizeWeak#` | Explicit finalization marks the weak dead, invokes registered supported C callbacks, and returns the actual Haskell action to the caller. Returning rather than running that action is intentional GHC primop behavior. Automatic GC finalization is absent; context close discards outstanding callbacks instead of executing them. |
| `addCFinalizerToWeak#` | Accepts only source-certified one-address callbacks (`libdwPoolRelease`, `backtraceFree`, and `free` for owned allocation bases), with zero environment flag. Arbitrary callbacks and the two-address/environment ABI are unsupported. Callbacks run through explicit finalization only. |

This limitation is not shared by stable names: `makeStableName#` really uses a
weak identity map and does not retain its referent. Stable pointers intentionally
root their referents; their separate native interoperability limits appear below.

Details: [explicit weak finalization](weak-explicit.md), [C finalizers](c-finalizers.md),
[stable names](stable-names.md).

## Addresses, pinning and pointer-containing storage

The important boundary is **native interoperability**, not the use of a pointer
abstraction. Managed addresses and owned native allocations support real checked
memory operations. Arbitrary unowned integer address bits do not grant memory or
FFI access. Pointer cells preserve managed references rather than inventing JVM
addresses; byte reinterpretation and foreign exposure therefore have limits.

| Primop | Current behavior and consequence |
| --- | --- |
| `addr2Int#` | Returns real native/numeric bits. Explicitly pinned arrays expose their original native allocation without copying; static literals may materialize a read-only native image and StablePtr handles may acquire persistent opaque native identities. Moving heap arrays reject projection even after unsafe freeze. Native authority is required; integer values do not root allocations or extend StablePtr lifetime after free. |
| `int2Addr#` | Preserves machine bits, including null. Recovers registered pinned-array and literal-image aliases and exact live current-context StablePtr identities; otherwise returns an opaque numeric address. Converting an owned malloc address to an integer and back does not itself recover dereferenceability. |
| `makeStablePtr#`, `deRefStablePtr#`, `eqStablePtr#` | Context-owned roots preserve lazy referents and live identity. Ordinary unsafe C imports can retain and return an opaque native identity; `freeStablePtr` or disposal releases it. Tokens expose no guest byte storage or native GHC closure ABI; safe calls and callback re-entry remain separate work. |
| `minusAddr#` | Managed addresses can be subtracted only within the same backing allocation. Native/numeric addresses retain machine-word subtraction; unrelated managed allocations have no synthetic numeric separation. |
| `remAddr#` | Uses unsigned remainder of the allocation-relative byte offset for managed addresses, real address bits for native/numeric addresses. It is not a physical-address alignment query for managed storage. |
| `ltAddr#`, `leAddr#`, `gtAddr#`, `geAddr#` | Order aliases within one managed allocation, or compare numeric/native bits. Unrelated managed allocations do not acquire a fabricated total address order. |
| `newPinnedByteArray#` | Allocates real stable native storage from creation, reclaimed with its last live owner/view. No copy-to-pin or per-call repinning. Ordinary `newByteArray#` remains moving heap storage. |
| `newAlignedPinnedByteArray#` | Same native storage guarantee with actual requested power-of-two alignment. |
| `byteArrayContents#` | Returns an alias retaining its backing storage, not an unconditional raw native address. Native projection has the `addr2Int#` restrictions above. |
| `mutableByteArrayContents#` | Returns an alias without copying or pinning; explicitly pinned backing has a real stable native address, moving heap backing does not. |
| `isByteArrayPinned#`, `isMutableByteArrayPinned#` | Report explicit native-backed pinning. Do not emulate GHC large-object or compact-region automatic pinning policy. |
| `isByteArrayWeaklyPinned#`, `isMutableByteArrayWeaklyPinned#` | Currently report the same explicit pinning flag as the strong queries; no separate GHC weak-pinning allocation policy. |
| `anyToAddr#` | Returns a weak opaque heap-identity handle. Equality/roundtrip work, but raw bytes, native projection and general pointer arithmetic do not. The caller must keep the evaluated referent alive, as required by GHC. |
| `addrToAny#` | Recovers a live context-owned heap handle, not an arbitrary GHC heap object at native address bits. |
| `shrinkMutableByteArray#` | Shrinks logical size in place and preserves aliases, but retains backing capacity. Partial truncation of a managed pointer cell is rejected. Host-injected raw byte arrays cannot be shrunk in place. |
| `resizeMutableByteArray#` | Shrinks in place without copying; growth copies the prefix into a new **unpinned** heap allocation. Partial truncation of a managed pointer cell is rejected. No extra promise about old aliases beyond GHC's contract. |

The following names spell out the pointer-cell boundary; they are not missing
implementations of numeric reads/writes or atomics.

| Primops | Pointer-storage restriction |
| --- | --- |
| `indexAddrArray#`, `readAddrArray#`, `writeAddrArray#`, `indexWord8ArrayAsAddr#`, `readWord8ArrayAsAddr#`, `writeWord8ArrayAsAddr#` | Managed pointer cells require allocation-owned storage, not a raw host `byte[]`. Whole-cell copies preserve references; numeric reads/partial overwrites of those cells reject. A raw/Sulong buffer exposure prevents later managed pointer-cell writes. Disjoint numeric fields remain usable. |
| `indexStablePtrArray#`, `readStablePtrArray#`, `writeStablePtrArray#`, `indexWord8ArrayAsStablePtr#`, `readWord8ArrayAsStablePtr#`, `writeWord8ArrayAsStablePtr#` | Same cell restrictions, retaining opaque stable-pointer handles. Storing a handle does not extend the stable-pointer registry lifetime after explicit release. |
| `indexAddrOffAddr#`, `readAddrOffAddr#`, `writeAddrOffAddr#`, `indexWord8OffAddrAsAddr#`, `readWord8OffAddrAsAddr#`, `writeWord8OffAddrAsAddr#` | Managed storage uses the cell rules above. Live owned native storage can contain real address bits, but native stores reject mutable-managed/opaque handle values with no native projection. Unknown read bits remain opaque numeric addresses. |
| `indexStablePtrOffAddr#`, `readStablePtrOffAddr#`, `writeStablePtrOffAddr#`, `indexWord8OffAddrAsStablePtr#`, `readWord8OffAddrAsStablePtr#`, `writeWord8OffAddrAsStablePtr#` | Managed cells retain handles; owned native cells store opaque native identities and recover exact live handles in the current context. Neither a cell nor a C copy extends lifetime after `freeStablePtr`. Tokens remain non-byte-addressable and are not native GHC heap addresses. |
| `atomicExchangeAddrAddr#`, `atomicCasAddrAddr#` | Managed cells retain/compare pointer identities; owned native storage requires real bits, including pinned storage addresses and opaque live StablePtr identities. Moving heap pointers cannot be stored as native bits. |
| `atomicCasWord8Addr#`, `atomicCasWord16Addr#`, `atomicCasWord32Addr#` | Managed storage works; owned-native narrow CAS requires the packaged Linux x86_64 helper. This is a native-path platform restriction, not a lack of managed atomic semantics. |

`newArray#`, `newSmallArray#`, `newByteArray#`, `newPinnedByteArray#`,
`newAlignedPinnedByteArray#`, and `resizeMutableByteArray#` retain Int-indexable
buffer bounds: requested lengths cannot exceed `Int.MAX_VALUE`, with actual
allocation limits lower. This is a target allocation limit, not a 64-bit
arithmetic limitation. Atomic implementations using locks/full fences are
implementations, not semantic gaps merely because they are not lock-free.

Details: [native address projection](native-addresses.md), [managed pinning](pinned-memory.md),
[scalar memory utilities](scalar-memory-utilities.md),
[aligned pointer cells](aligned-scalar-memory.md),
[unaligned scalar memory](unaligned-scalar-memory.md),
[address atomics](atomic-address.md).

## Compact regions and executable bytecode objects

| Primop | Current behavior and consequence |
| --- | --- |
| `compactNew#` | Creates a managed copied-graph region, not a contiguous GHC heap region. Does not provide GHC's avoidance of tracing every interior object during GC. |
| `compactResize#` | Changes target region-capacity accounting, not a contiguous native reservation. |
| `compactSize#` | Reports target capacity accounting in 4-KiB units, not exact GHC heap bytes or JVM object size. |
| `compactAdd#` | Copies/forces immutable graphs; resumable asynchronous forcing during addition is not implemented. Plain addition on cycles rejects with a diagnostic directing callers to the sharing variant. |
| `compactAddWithSharing#` | Preserves sharing and cycles, but has the same asynchronous-forcing restriction. |
| `compactGetFirstBlock#` | Exports a versioned THC graph image, **not GHC's wire format**. Images are limited to 256 MiB and currently usable only in their originating context; opaque external addresses cannot be serialized as native bits. |
| `compactGetNextBlock#` | Continues that same context-local image in checked 64-KiB blocks, with the same format/size restrictions. |
| `compactAllocateBlock#` | Allocates blocks for that THC image, not arbitrary GHC heap images or cross-context/process import. Abandoned raw imports are retained until context disposal. |
| `compactFixupPointers#` | Reconstructs a fresh graph using originating-context constructor metadata. Corrupt/incompatible images fail through the API's `Nothing` result; portable constructor resolution is not implemented. |
| `newBCO#` | Decodes and executes the documented GHC 9.14.1 **scalar opcode/ABI subset**, not arbitrary GHCi bytecode. One physical 64-bit word per argument; unsupported opcodes and packed subword/tuple/vector result conventions reject. |
| `mkApUpd0#` | Creates an updating wrapper for that scalar BCO subset. Native calls, info-table/PACK/AP instructions, case-continuation BCOs, breakpoints, asynchronous suspension and delimited capture through the interpreter remain unsupported. |

Details: [compact regions and serialization](compact-regions.md),
[BCO opcode list and ABI](ghc-bco.md).

## Continuations, liveness and heap/stack inspection

| Primop | Current behavior and consequence |
| --- | --- |
| `prompt#` | Synchronous reusable/multi-shot IO continuations work. Capturing applications with unboxed tuple/vector inputs and composing delimited capture with one-shot asynchronous suspension are not yet established. |
| `control0#` | Same supported control slice and composition/input restrictions as `prompt#`; saved frames share heap effects rather than rolling them back. |
| `annotateStack#` | Real lazy annotations follow dynamic stack extent and supported saved continuations. Snapshots are managed metadata, not raw GHC `ANN_FRAME` memory. Mixed async/delimited composition has the restriction above. |
| `keepAlive#` | Retains the reference through actual completion (including supported bytecode suspension), then issues a reachability fence. Direct vector continuation results reject; scalar, tuple and supported sum results work. This boundary is not by itself a GHC parity defect: GHC's continuation-style primops also restrict callback results to one machine word despite their polymorphic signatures. |
| `closureSize#` | Returns the size of THC's detached 64-bit-word closure image, not measured JVM allocation size or GHC RTS layout size. |
| `unpackClosure#` | Returns a detached THC word image plus separate lazy pointer fields. Pointer words in the byte image are zero; the info address identifies a THC descriptor, not a native GHC info table. Opaque host values have a one-word image. |
| `getApStackVal#` | Always reports non-`AP_STACK`: flag `0` and the original argument. Private THC continuation records are not exposed as GHC `AP_STACK` closures. |
| `getCCSOf#` | Returns null; no GHC cost-centre profiling stack. |
| `getCurrentCCS#` | Returns null for the same non-profiling target. |
| `clearCCS#` | Executes its action without a cost-centre profiling effect. |
| `whereFrom#` | Returns `0` and leaves the destination untouched; no closure IPE provenance table. |

There is also a **known bytecode JIT-retention issue** for the inlining-enabled
continuation dispatch workload: results, cleanup and entry counts are correct,
but the compiled handler is invalidated. An annotation-free control reproduces
it; do not mistake it for broken annotation semantics or a passing JIT-stability
test. It affects the recorded `prompt#`/`control0#`/`annotateStack#` workload.

No additional deficiency is recorded here for `newPromptTag#`, `touch#` or
`noDuplicate#`. In particular, exclusive thunk evaluation makes a no-op
`noDuplicate#` reasonable; lack of a GHC-specific mechanism is not itself a bug.

Details: [delimited continuations](delimited-continuations.md),
[closure inspection and recorded JIT issue](closure-inspection.md),
[liveness implementation](../src/main/kotlin/thc/runtime/KeepAlive.kt).

## Enumeration constructors

| Primop | Current behavior and consequence |
| --- | --- |
| `tagToEnum#` | Only saturated applications to concrete, parameterless ordinary enumeration types are admitted. Valid phantom-parameter enumerations and enumeration data-family instances are excluded by the exporter. |

This is not a restriction on `dataToTagSmall#` or `dataToTagLarge#`, which admit
parameterized algebraic families and data-family representation types.
Details: [tag-to-enum contract](tag-to-enum.md).

## Scalar floating-point portability

These are numerical implementation choices, not missing operations or a promise
that every platform's libm produces identical bits.

| Primops | Target rule |
| --- | --- |
| `expFloat#`, `expm1Float#`, `logFloat#`, `log1pFloat#`, `sinFloat#`, `cosFloat#`, `tanFloat#`, `asinFloat#`, `acosFloat#`, `atanFloat#`, `sinhFloat#`, `coshFloat#`, `tanhFloat#`, `powerFloat#` | Java Math with widening to Double and narrowing to Float; no bit-identical native libm guarantee. |
| `expDouble#`, `expm1Double#`, `logDouble#`, `log1pDouble#`, `sinDouble#`, `cosDouble#`, `tanDouble#`, `asinDouble#`, `acosDouble#`, `atanDouble#`, `sinhDouble#`, `coshDouble#`, `tanhDouble#`, `**##` | Java Math rather than the platform GHC libm; same portability caveat. |
| `asinhFloat#`, `acoshFloat#`, `atanhFloat#`, `asinhDouble#`, `acoshDouble#`, `atanhDouble#` | Stable shared log/log1p formulas (Float through Double). Recorded corpus tolerance is not a universal correctly-rounded guarantee. |

NaN payload/sign selection in arithmetic is not generally promised; this does
not remove raw-bit movement/cast support. Undefined GHC conversions or shifts
are not labeled missing functionality here.
Details: [floating scalar contracts and tests](floating-primitives.md).

## Floating SIMD portability

| Primops | Target floating-point rule |
| --- | --- |
| `minFloatX4#`, `minFloatX8#`, `minFloatX16#`, `minDoubleX2#`, `minDoubleX4#`, `minDoubleX8#` | Java vector minimum: either NaN operand produces NaN (payload/sign unspecified); mixed signed zeros produce negative zero irrespective of operand order. Native GHC vector lowering can choose different NaN/zero-tie behavior. |
| `maxFloatX4#`, `maxFloatX8#`, `maxFloatX16#`, `maxDoubleX2#`, `maxDoubleX4#`, `maxDoubleX8#` | Java vector maximum: either NaN operand produces NaN (payload/sign unspecified); mixed signed zeros produce positive zero irrespective of operand order. Same native portability caveat. |

Finite-input native comparisons and Java edge-case checks are separate evidence;
the tests do not assert universal native instruction bit parity. No additional
“partial” score is assigned for using correctly typed JDK vectors or for lacking
a formal equivalence proof. Details: [floating vector min/max](floating-vector-minmax.md).

## Performance hints and tracing

| Primops | Deliberate policy |
| --- | --- |
| `prefetchByteArray0#`, `prefetchByteArray1#`, `prefetchByteArray2#`, `prefetchByteArray3#` | No-op cache hints; no memory load or hardware-prefetch guarantee. |
| `prefetchMutableByteArray0#`, `prefetchMutableByteArray1#`, `prefetchMutableByteArray2#`, `prefetchMutableByteArray3#` | Same no-op policy. |
| `prefetchAddr0#`, `prefetchAddr1#`, `prefetchAddr2#`, `prefetchAddr3#` | Same no-op policy; opaque addresses are not dereferenced. |
| `prefetchValue0#`, `prefetchValue1#`, `prefetchValue2#`, `prefetchValue3#` | Same no-op policy; lifted payloads are not forced. |
| `traceEvent#` | Emits escaped NUL-terminated text to context stderr, not a GHC eventlog. No event-selection flags, timestamps or eventlog-tool integration. |
| `traceBinaryEvent#` | Emits exactly the supplied payload as lowercase hex to context stderr. Length must fit `0..Int.MAX_VALUE`; zero length does not read the address. Same eventlog limitations. |
| `traceMarker#` | Emits escaped text with a marker label to context stderr, with the same eventlog limitations. |

Details: [hints and tracing](hints-and-tracing.md).

## Related limits that are not individual primops

The `enabled_capabilities` RTS data label reports snapshotted CPU capacity, not
the number of guest threads. Dynamic `setNumCapabilities`, GHC `-N` scheduling,
bound `forkOS`/foreign TLS, general native RTS memory/stack decoding, arbitrary
FFI callbacks and full GHC eventlog/profiling services are separate runtime work.
The original allocation-counter getter reports the same target bytes as the
setter primops above. Context disposal is not GHC shutdown-finalizer execution.
Compiler-library shared FastStrings, CAF retention and unique-counter data cells
are documented separately in [compiler RTS services](compiler-rts.md); their
support does not imply a native GHC object loader or complete GHC API coverage.

Core transport also has restrictions independent of any one primop: see the
[coverage guide](README.md#tuples-and-sums) for aggregate inputs/captures and the
[SIMD guest transport contract](simd-families.md) for vector boundaries. A
registered primop cannot make an otherwise unsupported whole program runnable.

When changing a behavior above, update this page, its detailed guide and the
machine-readable [capability notes](../scripts/core-capabilities.json) together.
Keep concrete counterexamples/rejections separate from coverage percentages and
from historical validation reports.
