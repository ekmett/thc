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

The
[generated checklist](primops.md) is the complete inventory; an operation absent
here has no specific infelicity recorded here, not a proof of equivalence on
every input. JVM/Sulong pointers, managed storage, typed vector representations,
and ordinary GHC input preconditions are not themselves missing functionality.
The tables distinguish unsupported behavior from intentional target choices
and performance-only hints. Linked implementation guides supply detail and
test commands.

## Narrow Core literals

Narrow literal tags preserve their signed or unsigned values when representation
metadata is absent or unconstrained. Both loaders reject malformed present proofs
and noncanonical or out-of-range values; ordinary machine literals do not acquire
narrow certification. Real exported 16-bit and 32-bit literals have dedicated
array-suite controls. A genuine GHC 8-bit literal with erased argument proof remains
unqualified; synthetic loader controls do not establish that compiler behavior.

## Aggregate-result local joins

Fixture-free controls cover tuple-result ownership and local tuple capture.
Genuine GHC-exported tuple-result join capture remains unqualified; see
[tuple joins](tuple-joins.md) for the owning controls and scope.

Fixture-free mixed sum-result joins retain lazy identity and clear inactive
references through zero-arity and recursive returns in both backends. Genuine
GHC-exported sum-result joins and `runRW#` transfers remain unqualified; see
[sum results](sum-results.md) for the owning controls.

Fixture-free empty-argument controls cover ordinary calls, PAPs, overapplication
and tail transfers. Genuine GHC-exported ordinary empty-argument call boundaries
remain unqualified; see [empty tuple inputs](empty-tuple-inputs.md).

## Original working-directory foreign calls

| Original declaration | Current behavior and consequence |
| --- | --- |
| ghc-internal `open` | The explicit native provider retains native flags, unsigned mode transport, descriptor reservation and safe/interruptible request ownership on Linux x86_64 and Darwin x86_64/arm64. Qualification covers synthetic Word32 Core open and owning native controls. Actual installed GHC wrappers, native Word16 wrapper execution, the full three-safety Core matrix and Loom-hosted open remain unqualified. See [open and cancellation](native-file-provider.md#open-and-cancellation). |
| Unix 2.8.8.0 `chdir` / `getcwd` | Both backends use the explicit Linux x86_64 NativeIO context's directory descriptor; process CWD stays unchanged. `getcwd` supports non-null caller-owned buffers and rejects GNU NULL allocation. Verified physical naming can return EACCES for inaccessible ancestors where native process `getcwd` succeeds; relative IO remains descriptor-based. See [native files](native-file-provider.md#working-directory-and-path-operations) for lifetime and platform requirements. |
| Process 1.6.26.1 `runInteractiveProcess`, `getProcessExitCode`, `waitForProcess`, `terminateProcess` | Both backends preserve the original unsafe/interruptible declarations on Linux x86_64 with an explicit process grant. Only launched context-owned children are valid; real PIDs remain observable and retained IDs cannot be rebound after reuse. Interruptible wait saves result and errno before delivery and never replays a reap. Credential changes, unsupported flags, auto-reaping SIGCHLD policies and arbitrary host PIDs reject; stable host signal/reaping policy is required. See [owned process transport](process-lifecycle.md). |
| Unix 2.8.8.0 directory streams | Exact unsafe opendir/fdopendir/closedir/readdir/d_name/free_dirent calls use context-owned handles on Linux x86_64 glibc 2.23+. Readdir preserves raw names and EOF errno; views expire at the next read or close. Successful fdopendir consumes only its input guest descriptor. See [native files](native-file-provider.md#directory-streams) for ownership and validation. |
| Win32 2.14.2.1 directory scans | Exact unsafe FindFirstFileW/FindNextFileW/FindClose calls use context-owned search handles on Windows x86_64. Caller-owned UTF-16 find-data survives close, errors are captured at the native call and observed by original GetLastError, and context disposal closes remaining searches. Both backends require the fixed NativeIO factory for scans; full installed-library closure export and other Windows file APIs remain separate. See [Windows scans](windows.md#original-win32-directory-scans). |
| ghc-internal Windows encoding/errors | Exact unsafe GetACP/GetConsoleCP/GetCPInfo/IsDBCSLeadByteEx/MultiByteToWideChar/WideCharToMultiByte use actual Windows code pages and flags on both backends. GetLastError shares captured errors with directory calls; maperrno/maperrno_func preserve the original C mapping and separate errno slot. base_getErrorMessage/LocalFree use context-owned native message allocations with allocator and lifetime checks. Native access is required, without filesystem authority. Other Windows IO and automatic exception conversion remain separately validated scopes. See [Windows encoding](windows.md#original-windows-code-pages-and-errors). |
| Unix 2.8.8.0 `rmdir`; Unix and `ghc-internal` `readlink` | Both backends retain native context-relative pathname behavior and exact unsafe CInt results. Rmdir uses authenticated AT_REMOVEDIR removal; readlink stages only the returned bytes, adds no NUL, and preserves the output on failure. See [native files](native-file-provider.md#working-directory-and-path-operations). |

## Sparks and thread scheduling

| Primop | Current behavior and consequence |
| --- | --- |
| `par#` | Returns `1`; discards hints by default. With opt-in `thc.SparkQueueCapacity`, submits suitable context-owned AST/bytecode or updating GHC BCO thunks with continuation capture for speculative WHNF evaluation without forcing on the caller. |
| `spark#` | Returns the identical, unforced payload; completes its state operand before optional queue submission. |
| `numSparks#` | Returns queued entries in the context-owned opt-in queue; defaults to `0`. |
| `getSpark#` | Dequeues an unclaimed thunk with flag `1`, or returns flag `0` and the pinned GHC boxed `False` filler when the queue is empty or disabled. |
| `fork#` | Creates a Truffle-managed platform thread by default, or one virtual thread per guest thread with opt-in `thc.ThreadHosting=loom`. Thread creation must be allowed by the embedding. Platform mode clears inherited CPU affinity; Loom routes unmounted work between exclusive logical HEC workers. Fork admission enables ordinary asynchronous polling before child publication on both backends, including with `asyncExceptions: false`; see `killThread#` below. |
| `forkOn#` | Same thread and delivery requirements as `fork#`. Chooses a dense logical capability modulo the context's current logical capability count, then maps modulo its immutable eligible CPU capacity. Native affinity is **best effort**: Linux requests a per-thread pin; Windows requests advisory CPU Sets and declines unresolved multi-group topology; macOS, unavailable native access, or a rejected request run unpinned without failing the fork. |
| `threadStatus#` | Capability is a context-local assignment, not a measurement of the currently executing physical CPU. The lock flag records a `forkOn#` request, **not successful OS affinity**. Ordinary threads share logical capabilities. |
| `listThreads#` | Lists context-owned guest identities, not every JVM thread. Retained completed identities and, in platform mode, host carriers between guest invocations can appear; ordering is unspecified. |
| `isCurrentThreadBound#` | In either hosting mode, returns `1` inside an admitted safe managed foreign reverse entry and `0` for ordinary guest entries and forks. Unsafe activations reject reverse entry before changing thread state. Callback identities stay on their native origin thread; supported native callbacks retain their checked scalar/address ABI; `forkOS` remains unsupported. |
| `setThreadAllocationCounter#` | Accounts JVM heap bytes during outer guest-entry extents, including runtime bookkeeping and excluding native/Sulong allocations and host work between entries. Requires JVM thread-allocation accounting support; Loom reads/resets reject. Does **not** enforce allocation limits. |
| `setOtherThreadAllocationCounter#` | Same accounting and missing allocation-limit enforcement, for the selected context-owned thread. |

Spark queues are bounded, default disabled, and use one managed worker. Only
this context's original AST/bytecode thunks with async continuation capture are
admitted; unsupported hints and overflow are discarded. Failures remain on the
shared thunk; cooperative worker cancellation preserves its saved continuation
for demand and stops further speculative work. See [thread scheduling](thread-scheduling.md).
No parallel speedup is claimed. Real forks are independent of the hint policy. CPU pins can be inherited by native
or JVM helper threads. The version-pinned Graal compiler-worker listener resets
recognized workers before compilation, including replacement workers; helpers
created earlier during initialization and unrecognized workers are not covered.
Use the public [THC affinity API](cpu-affinity-api.md) to observe native request
acceptance; `threadStatus#` cannot supply that information.

**Virtual-thread safety:** platform hosting is the default. Opt-in Loom hosting
uses a lifetime routing executor per guest virtual thread, not the JVM's shared
default scheduler; only its HEC platform carriers receive native pins.
Both the
[Linux](../src/main/java/thc/runtime/LinuxCpuAffinity.java) and
[Windows](../src/main/java/thc/runtime/WindowsCpuAffinity.java) affinity paths
refuse native affinity changes on virtual threads, including when an embedding
enters from one. Keep those guards: native pins on a migrating virtual thread
could affect unrelated carrier work. Safe/interruptible foreign execution and
blocking callbacks release the exclusive HEC permit; replacement carriers share
that permit, preserving native-origin callback TLS. Process-signal readers use
the same admission protocol. Explicit per-thread allocation counters remain
unavailable. See the exact setup and limitations in
[thread hosting](thread-scheduling.md#thread-hosting). The helper-inheritance
caveat above still applies.

Details: [scheduling and affinity](thread-scheduling.md),
[thread status](thread-status.md), [thread inventory](thread-inventory.md).

## Exceptions, blocking and transactions

Public load requests accept a Boolean `asyncExceptions`. When omitted, it
defaults to `false` on both backends, speculating on a single guest admission
origin until concurrency is admitted. Explicit `true` enables polling eagerly.
Ordinary calls, cases, lets, local joins, mask/catch scopes and shared-thunk
updates always retain continuation capture across asynchronous suspension.
The representation and composition limits below still apply.

| Primop | Current behavior and consequence |
| --- | --- |
| `catch#` | Uses the ordinary concrete recursive typed result layout in both backends, including supported integral, floating, address, boxed, zero-width/nested tuple, sum and vector carriers. `BoxedRep Nothing` results retain traced pointer storage without gaining evaluatedness. The outer result keeps exactly two logical fields, State# and the action result. Unresolved runtime layouts and shapes unsupported by the generic transport still reject. |
| `raiseIO#` | Accepts concrete lifted or unlifted boxed exception payloads independently of its concrete typed result layout. Lifted payloads remain lazy; unlifted boxed references retain their identity. Unknown payload levity and scalar/unboxed payloads reject according to the pinned GHC signature. |
| `maskAsyncExceptions#` | Implements interruptible masking/restoration with the same concrete typed result layouts as `catch#`; no exception-specific scalar whitelist. |
| `maskUninterruptible#` | Implements uninterruptible masking/restoration, with the same supported result representations as `maskAsyncExceptions#`. |
| `unmaskAsyncExceptions#` | Implements unmasking/restoration, with the same supported result representations as `maskAsyncExceptions#`. |
| `killThread#` | Both ordinary backends support `throwTo` through saved guest continuations, including external delivery to a live or saved delimited catch. `asyncExceptions: false` suppresses ordinary polls while the per-context single guest admission origin assumption holds; fork/signal publication or different-origin public admission invalidates it before effects. Self-delivery remains mandatory under both masks. The reached handler acknowledges the original request without forcing its payload; the interrupted child's one-shot continuation is not cloned into the multi-shot image. Delimited invocations drain scheduling cuts and AST stack spills through separate one-shot owners; new `control0#` capture across those parked caller chains still rejects. Prepared AST code retains continuation capture and uses runtime-context admission for default-off polling; an explicitly eager policy is preserved through preparation. Arbitrary Java/native foreign frames do not gain resumable interruption. Resumable sends to host carriers outside guest invocations are no-ops, not messages queued for a later unrelated host call. |
| `waitRead#`, `waitWrite#` | Wait for context-owned native descriptors on Linux x86_64 and Darwin x86_64/arm64. Close or replacement wakes the original wait without following descriptor reuse. Bad descriptors raise the original lazy `blockedOnBadFD`; opaque embedding streams remain unsupported. See [descriptor waits](file-wait.md). |
| `takeMVar#` | Blocking transfers and supported interruption work, but no GC-driven `BlockedIndefinitelyOnMVar` detection. A wait with no future producer needs supported interruption or embedding cancellation to end. |
| `putMVar#` | Same missing deadlock exception for a blocked put; FIFO handoff and cancellation-before-commit are implemented. |
| `readMVar#` | Same missing deadlock exception for a blocked read; reader broadcast is implemented. |
| `atomically#` | Private autonomous stack cuts retain the live attempt and remaining scopes on both backends; they do not replay prefixes or publish writes. External async interruption aborts the attempt. An abandoned shared thunk restarts the original action under a fresh transaction; it does not restore a retired log. Explicit checkpoint/delimited capture across transactions remains unsupported. |
| `retry#` | Async delivery cancels the wait before leaving the attempt. No GC-driven `BlockedIndefinitelyOnSTM`: retries with no possible future wakeup, including empty read sets, wait until cancellation/disposal. |
| `catchRetry#` | A retry rolls back the failed branch and runs the alternative. Async delivery aborts nested state and continues outward without running that alternative. Explicit checkpoint/delimited capture remains unsupported. |
| `catchSTM#` | Catches synchronous Haskell exceptions with nested rollback. Async delivery aborts nested state and continues outward without invoking this handler. Explicit checkpoint/delimited capture remains unsupported. |
| `readTVar#` | Validated reads belong to the current live attempt. Internal stack continuations retain its association, not a copy of the log; external interruption retires it. Explicit checkpoint/delimited capture remains unsupported. |
| `writeTVar#` | Writes remain private until atomic commit and are discarded on async abort. Explicit checkpoint/delimited capture remains unsupported. |
| `raiseDivZero#` | Raises the original GHC exception closure lazily, with no raiser-specific result restriction. Supported vector, unboxed-sum and nested-tuple bottom results use the generic layouts. Requires one exact unlifted empty-tuple operand. |
| `raiseOverflow#` | Same bottom-result behavior, with the original overflow exception. |
| `raiseUnderflow#` | Same bottom-result behavior, with the original underflow exception. |

Do not apply the transaction-frame restriction to `newTVar#` or `readTVarIO#`:
they do not require such a frame. The absence of GC-driven deadlock exceptions
is separate from functioning MVar handoff and STM read-set wakeups.

The [concrete exception-layout controls](async-exceptions.md#concrete-exception-result-layouts)
distinguish THC's typed-result semantics from GHC's narrower native continuation
ABI and link to the current generic result-layout support.

Details: [asynchronous exceptions](async-exceptions.md), [MVars](managed-mvars.md),
[STM](stm.md), [arithmetic exception validation](../src/main/java/thc/runtime/CoreArithmeticExceptions.java)
and [raising](../src/main/java/thc/runtime/RaiseArithmeticException.java).

## Weak pointers and finalization

| Primop | Current behavior and consequence |
| --- | --- |
| `mkWeak#` | Strongly retains its lazy key, value and Haskell action until explicit finalization or context disposal. **No automatic weak-key/ephemeron collection**; otherwise unreachable resources can remain for the context lifetime. Dropping the `Weak#` does not remove its registration. |
| `mkWeakNoFinalizer#` | Without callbacks, identical key/value carriers are weakly held; raw managed `MutVar#` or `MVar#` keys can also own distinct lazy values without rooting an otherwise unreachable key/value cycle. Finalization/disposal detaches those values. Distinct values with other key carriers remain strongly retained; general ephemeron collection is absent. |
| `deRefWeak#` | Returns flag 0 after explicit finalization or collection of an identity-only or actionless raw `MutVar#` or `MVar#` key. Otherwise returns the original lazy value without forcing it. |
| `finalizeWeak#` | Explicit finalization marks the weak dead, invokes registered supported C callbacks, and returns the actual Haskell action to the caller. Returning rather than running that action is intentional GHC primop behavior. JVM collection can automatically retire one canonical owned malloc free on an actionless identity-only or raw `MutVar#`/`MVar#` key; general automatic finalization remains absent. Context close discards outstanding callbacks instead of executing them and disposes remaining native allocations. |
| `addCFinalizerToWeak#` | Accepts source-certified C callbacks: zero calls `f(object)`, every nonzero flag calls `f(environment, object)`. Typed package callbacks require a retained normalized `FunPtr (Ptr a -> IO ())` or `FunPtr (Ptr env -> Ptr a -> IO ())` declaration and a matching exact rooted `void(pointer)` or `void(pointer, pointer)` definition in a completely linked component. Both arguments use the ordinary typed address transport and shared native borrowing. Unknown labels reject. The reserved `free` remains one-address with zero flag and checked owned allocation bases. A single canonical current-context owned free on an actionless identity-only or raw `MutVar#`/`MVar#` key weakly references its direct malloc owner (or null) and can retire through JDK Cleaner without a managed GC request. Busy borrows/free/realloc defer until completion without waiting or another collection; failures are retained without replay. Other callbacks, including a second callback, restore strong retention of the original key and value and remain explicit-only; a collected registration returns 0. See [C finalizers](c-finalizers.md). |

This limitation is not shared by stable names: `makeStableName#` really uses a
weak identity map and does not retain its referent. Stable pointers intentionally
root their referents; their separate native interoperability limits appear below.

Details: [managed weak registrations](weak-explicit.md), [C finalizers](c-finalizers.md),
[stable names](stable-names.md).

## Addresses, pinning and pointer-containing storage

The important boundary is **native interoperability**, not the use of a pointer
abstraction. Managed addresses and owned native allocations support real checked
memory operations. Arbitrary unowned integer address bits do not grant memory or
FFI access. Pointer cells preserve managed references rather than inventing JVM
addresses; byte reinterpretation and foreign exposure therefore have limits.

Original `memcpy` declarations from `ghc-internal` and installed `ram-0.22.1`
use the same checked address-copy leaf. The `ram` declaration requires unsafe
`ccall`, two `Addr#` operands, `Word64#` length and a State#/Addr# tuple result;
it does not admit the distinct `array` byte-array ABI. Copies retain destination
identity and reject overlap, out-of-bounds ranges, stale ownership and unowned
numeric pointers before mutation. Admitting this declaration does not establish
support for every dependency of the original package closure.

| Primop | Current behavior and consequence |
| --- | --- |
| `addr2Int#` | Returns real native/numeric bits. Explicitly pinned arrays expose their original native allocation without copying; static literals may materialize a read-only native image and StablePtr handles may acquire persistent opaque native identities. Moving heap arrays reject projection even after unsafe freeze. Native authority is required; integer values do not root allocations or extend StablePtr lifetime after free. |
| `int2Addr#` | Preserves machine bits, including null. Recovers registered pinned-array and literal-image aliases and exact live current-context StablePtr identities; otherwise returns an opaque numeric address. Converting an owned malloc address to an integer and back does not itself recover dereferenceability. |
| `makeStablePtr#`, `deRefStablePtr#`, `eqStablePtr#` | Context-owned roots preserve lazy referents and live identity. Ordinary unsafe C imports can retain and return an opaque native identity; `freeStablePtr` or disposal releases it. Tokens expose no guest byte storage or native GHC closure ABI; safe calls and callback re-entry remain separate work. |
| `minusAddr#` | Managed addresses can be subtracted only within the same backing allocation. Native/numeric addresses retain machine-word subtraction; unrelated managed allocations have no synthetic numeric separation. |
| `remAddr#` | Uses unsigned remainder of the allocation-relative byte offset for managed addresses, real address bits for native/numeric addresses. It is not a physical-address alignment query for managed storage. |
| `ltAddr#`, `leAddr#`, `gtAddr#`, `geAddr#` | Order aliases within one managed allocation, or compare numeric/native bits. Unrelated managed allocations do not acquire a fabricated total address order. |
| `newPinnedByteArray#` | Allocates real stable native storage from creation, reclaimed with its last live owner/view. No copy-to-pin or per-call repinning. Ordinary `newByteArray#` follows the [creation policy](bytearrays.md): native in standalone launchers, heap by default in embedded contexts, without the strong-pinned contract. |
| `newAlignedPinnedByteArray#` | Same native storage guarantee with actual requested power-of-two alignment. |
| `byteArrayContents#` | Returns an alias retaining its backing storage, not an unconditional raw native address. Native projection has the `addr2Int#` restrictions above. |
| `mutableByteArrayContents#` | Returns an alias without copying or pinning; explicitly pinned backing has a real stable native address, moving heap backing does not. |
| `isByteArrayPinned#`, `isMutableByteArrayPinned#` | Report explicit native-backed pinning. Do not emulate GHC large-object or compact-region automatic pinning policy. |
| `isByteArrayWeaklyPinned#`, `isMutableByteArrayWeaklyPinned#` | Report stable native backing, including ordinary arrays under the opt-in native creation policy. Such ordinary arrays remain strong-unpinned and compactable. |
| `anyToAddr#` | Returns a weak opaque heap-identity handle. Equality/roundtrip work, but raw bytes, native projection and general pointer arithmetic do not. The caller must keep the evaluated referent alive, as required by GHC. |
| `addrToAny#` | Recovers a live context-owned heap handle, not an arbitrary GHC heap object at native address bits. |
| `shrinkMutableByteArray#` | Shrinks logical size in place and preserves aliases, but retains backing capacity. Partial truncation of a managed pointer cell is rejected. Host-injected raw byte arrays cannot be shrunk in place. |
| `resizeMutableByteArray#` | Shrinks in place without copying; growth copies the prefix into a new **strong-unpinned** allocation using the context's guest storage policy (heap by default). Raw host arrays retain their heap resize path. Partial truncation of a managed pointer cell is rejected. No extra promise about old aliases beyond GHC's contract. |

The following names spell out the pointer-cell boundary; they are not missing
implementations of numeric reads/writes or atomics.

Package C pointer results preserve their Sulong carrier. Known aliases retain
their backing's bounds, mutability and borrowing checks. External C buffers
support scalar/vector reads and writes, pointer-cell access, copies, fills,
NUL-string reads and descriptor transfers using the requested width/count;
they do not acquire a fabricated extent or a managed `free` capability.
Native access and the original context/library must remain available. The C
program and Haskell `withForeignPtr`/`keepAlive#` usage retain responsibility for
external buffer lifetime. Converting arbitrary integer bits to `Addr#` does not
grant this access. External-buffer atomic operations are not yet supported.

| Primops | Pointer-storage restriction |
| --- | --- |
| `indexAddrArray#`, `readAddrArray#`, `writeAddrArray#`, `indexWord8ArrayAsAddr#`, `readWord8ArrayAsAddr#`, `writeWord8ArrayAsAddr#` | Managed pointer cells require allocation-owned storage, not a raw host `byte[]`. Whole-cell copies preserve references; numeric reads/partial overwrites of those cells reject. A raw/Sulong buffer exposure prevents later managed pointer-cell writes. Disjoint numeric fields remain usable. |
| `indexStablePtrArray#`, `readStablePtrArray#`, `writeStablePtrArray#`, `indexWord8ArrayAsStablePtr#`, `readWord8ArrayAsStablePtr#`, `writeWord8ArrayAsStablePtr#` | Same cell restrictions, retaining opaque stable-pointer handles. Storing a handle does not extend the stable-pointer registry lifetime after explicit release. |
| `indexAddrOffAddr#`, `readAddrOffAddr#`, `writeAddrOffAddr#`, `indexWord8OffAddrAsAddr#`, `readWord8OffAddrAsAddr#`, `writeWord8OffAddrAsAddr#` | Managed storage uses the cell rules above. Live owned native storage can contain real address bits, but native stores reject mutable-managed/opaque handle values with no native projection. Unknown read bits remain opaque numeric addresses. |
| `indexStablePtrOffAddr#`, `readStablePtrOffAddr#`, `writeStablePtrOffAddr#`, `indexWord8OffAddrAsStablePtr#`, `readWord8OffAddrAsStablePtr#`, `writeWord8OffAddrAsStablePtr#` | Managed cells retain handles; owned native cells store opaque native identities and recover exact live handles in the current context. Neither a cell nor a C copy extends lifetime after `freeStablePtr`. Tokens remain non-byte-addressable and are not native GHC heap addresses. |
| `atomicExchangeAddrAddr#`, `atomicCasAddrAddr#` | Managed cells retain/compare pointer identities; owned native storage requires real bits, including pinned storage addresses and opaque live StablePtr identities. Moving heap pointers cannot be stored as native bits. |
| `atomicCasWord8Addr#`, `atomicCasWord16Addr#` | Heap byte storage works; native-backed pinned storage uses the exact-width packaged helper on Linux x86_64 and macOS x86_64/arm64. Owned native malloc storage remains Linux x86_64 only. |

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
| `compactAdd#` | Copies/forces immutable graphs. Asynchronous child forcing retains a one-shot traversal without replaying completed children; private shells are published only on success. Plain addition on cycles rejects with a diagnostic directing callers to the sharing variant. |
| `compactAddWithSharing#` | Same resumable traversal, preserving sharing and cycles. A parked or abandoned copy does not keep the region's active-writer guard; resumed segments reacquire it. |
| `compactGetFirstBlock#` | Exports a versioned THC graph image, **not GHC's wire format**. Images are limited to 256 MiB and currently usable only in their originating context; opaque external addresses cannot be serialized as native bits. |
| `compactGetNextBlock#` | Continues that same context-local image in checked 64-KiB blocks, with the same format/size restrictions. |
| `compactAllocateBlock#` | Allocates blocks for that THC image, not arbitrary GHC heap images or cross-context/process import. Abandoned raw imports are retained until context disposal. |
| `compactFixupPointers#` | Reconstructs a fresh graph using originating-context constructor metadata. Corrupt/incompatible images fail through the API's `Nothing` result; portable constructor resolution is not implemented. |
| `newBCO#` | Decodes and executes the documented GHC 9.14.1 **opcode/ABI subset**, including scalar cases, internal tuple continuations, packed subword stacks and captured AP/PAP application. Function arity is independent of continuation bitmap width. Ordinary external function entry still requires one word per argument; external Core tuple and SIMD conventions require unsupported ABI adapters. |
| `mkApUpd0#` | Creates an updating wrapper for a zero-arity BCO with an empty entry bitmap. Native calls, info-table/PACK instructions, breakpoints and explicit delimited capture through the interpreter remain unsupported. One-shot asynchronous and stack cuts preserve pending work and the shared update. Context-owned updating BCO/AP thunks are eligible for the existing opt-in spark pool; cooperative worker cancellation leaves their work resumable by demand. |

Details: [compact regions and serialization](compact-regions.md),
[BCO opcode list and ABI](ghc-bco.md).

## Continuations, liveness and heap/stack inspection

| Primop | Current behavior and consequence |
| --- | --- |
| `prompt#` | Reusable/multi-shot IO continuations support saved catch/mask scopes, async delivery and invocation-local scheduling owners. Capturing applications with unboxed tuple/vector inputs and new capture across a parked one-shot caller chain are not yet supported. |
| `control0#` | Same supported control slice and composition/input restrictions as `prompt#`; saved frames share heap effects rather than rolling them back. |
| `annotateStack#` | Real lazy annotations follow dynamic stack extent and supported saved continuations. Snapshots are managed metadata, not raw GHC `ANN_FRAME` memory. Mixed async/delimited composition has the restriction above. |
| `keepAlive#` | Retains the reference through actual completion, including supported AST and bytecode suspension, with reachability fences on ordinary and exceptional exits. Continuations preserve scalar, direct vector, tuple and supported sum result representations; they may throw without producing any result carrier. Exact reference, State and continuation input requirements remain. |
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
[liveness implementation](../src/main/java/thc/runtime/KeepAliveExpression.java).

## Enumeration constructors

| Primop | Current behavior and consequence |
| --- | --- |
| `tagToEnum#` | Only saturated applications to concrete, parameterless ordinary enumeration types are admitted. An erased newtype result cast is supported with the original complete enum family and exact lifted scalar result carrier; using a newtype as the enum type is not supported. Valid phantom-parameter enumerations and enumeration data-family instances are excluded by the exporter. |

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

See [floating vector min/max](floating-vector-minmax.md) for the exact target
semantics. Native instruction bit parity is not guaranteed for NaNs or signed zero.

## Performance hints and tracing

| Primops | Deliberate policy |
| --- | --- |
| `prefetchByteArray0#`, `prefetchByteArray1#`, `prefetchByteArray2#`, `prefetchByteArray3#` | No-op cache hints; no memory load or hardware-prefetch guarantee. |
| `prefetchMutableByteArray0#`, `prefetchMutableByteArray1#`, `prefetchMutableByteArray2#`, `prefetchMutableByteArray3#` | Same no-op policy. |
| `prefetchAddr0#`, `prefetchAddr1#`, `prefetchAddr2#`, `prefetchAddr3#` | Same no-op policy; opaque addresses are not dereferenced. |
| `prefetchValue0#`, `prefetchValue1#`, `prefetchValue2#`, `prefetchValue3#` | Same no-op policy; lifted payloads are not forced. |
| `traceEvent#` | Ignored by default. The context-local `THC.Trace` sink enables escaped NUL-terminated text on stderr or `thc.RuntimeTrace` JFR events. |
| `traceBinaryEvent#` | Same sink selection. When enabled, preserves exactly the supplied bytes as lowercase hex. Length must fit `0..Int.MAX_VALUE`; zero length does not read the address. |
| `traceMarker#` | Same sink selection. Enabled markers preserve NUL-terminated text with a marker label/phase. |

Details: [hints and tracing](hints-and-tracing.md).

The selected GHC 9.14.1 `RtsFlags.TraceFlags.user` getter with header-derived
schema-2 RTS layout metadata reports whether the context's `THC.Trace` sink
is selected, independently of diagnostic counters and active JFR recordings. This single read-only CBool mapping does not implement a native RTS
image or GHC event-selection flags; unknown fields/widths and writes reject.
The supported producing layout and native-default difference are documented
in the tracing guide above.

## Related limits that are not individual primops

The `enabled_capabilities` RTS data label reports the context-local logical count,
initially snapshotted CPU capacity and mutable through original `setNumCapabilities`.
The eligible CPU count remains fixed; JVM pools and existing affinity are unchanged.
See [RTS event prerequisites and capabilities](rts-event-capabilities.md).
GHC `-N` scheduling,
bound `forkOS`/foreign TLS, general native RTS memory/stack decoding, arbitrary
FFI callbacks and full GHC eventlog/profiling services are separate runtime work.
The original allocation-counter getter reports the same target bytes as the
setter primops above. Context disposal is not GHC shutdown-finalizer execution.
Compiler-library shared FastStrings, CAF retention and unique-counter data cells
are documented separately in [compiler RTS services](compiler-rts.md); their
support does not imply a native GHC object loader or complete GHC API coverage.
The compiler's original `setHeapSize` evaluates its byte-count/state operands and
returns, ignoring the heap-size advisory: THC does not resize the process-wide
JVM heap, request GC, or invent mutable native RTS sizing flags.
Original `performGC`, `performMajorGC` and `performBlockingMajorGC` request JVM
collection and attempt pending eligible owned frees without waiting for borrows;
there are no GHC generation or completion guarantees. `getRTSStatsEnabled`
is false, and direct `getRTSStats` rejects without modifying its buffer; original
Haskell retains its disabled-statistics exception. `getMonotonicNSec` uses the
JVM monotonic clock with an arbitrary process-local origin. See
[GC/statistics/clock behavior](gc-stats-clock.md).
Original `stg_sig_install` supports GHC's INT/QUIT/HUP/TERM handlers in the
Linux x86_64 launcher with `-Xrs` on either ordinary backend; the first signal
worker invalidates speculative single-origin polling. Other signals, non-null masks and ordinary
embedding contexts remain outside that service. See
[process signal ownership and JVM consequences](process-signals.md).

Core transport also has restrictions independent of any one primop: see the
[coverage guide](aggregate-layout.md) for aggregate inputs/captures and the
[SIMD guest transport contract](simd-families.md) for vector boundaries. A
registered primop cannot make an otherwise unsupported whole program runnable.

When changing a behavior above, update this page, its detailed guide and the
machine-readable [capability notes](../bin/core-capabilities.json) together.
