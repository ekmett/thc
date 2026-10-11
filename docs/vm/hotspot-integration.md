# HotSpot integration

HotSpot owns Java object layout, roots, safepoints and reference processing.
Jam owns storage, marking and movement. The adapter connects them through
[a small C interface](https://github.com/ekmett/thc/blob/main/src/vm/src/adapter/thc_vm.h), compiled on each side with that
side's toolchain.

The backend is C++26 module `thc.jam`, with `:heap` and `:weak` partitions.
The pinned JDK builds as C++14. Opaque handles, integer offsets, pointer/count
pairs and callbacks cross the boundary; C++ objects, allocation ownership and
exceptions do not. There is no requirement to compile the whole JDK as C++26.

For thc's managed entrypoints, see [the integration handoff](thc-integration.md).
Those calls sit above this collector interface and return normal Java objects.

## Registering a collector

`UseJamGC` selects `CollectedHeap::Jam` through `GCConfig`. `JamArguments`
validates the configuration and constructs `JamHeap`, which implements
`CollectedHeap` and installs `JamBarrierSet`.

The `jamgc` build feature includes these sources. Jam also has its own VM
operation, GC names, management pool, Serviceability Agent heap type, JVMCI
identity and WhiteBox identity. Epsilon's original files are restored and
`UseEpsilonGC` keeps its original allocation-only behavior.

These tables and types are compiled into HotSpot. A native library cannot
register a new collector in a stock JVM. Once this integration has been built,
ABI-compatible backend changes can rebuild `libthc-vm` alone. Changes to the
host interfaces still require rebuilding `libjvm`.

## Reserving the heap

`Universe::reserve_heap` establishes the reservation and compressed-oop base.
The adapter checks the result before publishing objects:

* Compressed oops are enabled, object alignment is eight bytes and the shift is three.
* The base and protected prefix are nonzero and agree with the reservation.
* Each generation, including its prefix and copy reserve, fits in the 16 GiB domain.
* Capacities are fixed; large pages, alternate backing, compact headers and
  archived Java heap objects are disabled.

The [encoding derivation](architecture.md#a-stable-view-of-a-rotating-heap)
explains why these conditions let JVM narrow oops equal jam offsets.

The reservation uses HotSpot's ordinary address selection. Its ergonomic
preference is 32 GiB, or 2 TiB on the tested macOS path. An explicit
`HeapBaseMinAddress` remains the caller's choice. The preference is a hint;
validation must examine the returned base rather than assume the requested
placement succeeded.

Two `ContiguousSpace`s describe the published bounds for heap iteration and
serviceability. They do not allocate backing. Slow allocations and TLAB
reservations call `thc_vm_allocate` under `Heap_lock`. The adapter preflights
capacity so exhaustion returns to HotSpot's collection-and-retry path instead
of reaching jam's ordinary allocation abort.

## Collection phases

The host stops mutators and retires TLABs before opening a collection epoch.
The C interface separates tracing from movement:

| Operation | Contract |
| --- | --- |
| `thc_vm_begin` | Select minor or major and reset collected-generation marks and pointer declarations |
| `thc_vm_trace` | Trace roots through jam; ordinary old roots are live but untraversed during a minor |
| `thc_vm_remember` / `thc_vm_remembered` | Register exact old source slots; snapshot them at a safepoint |
| `thc_vm_forget` | Retire slots discarded by continuation thaw without reading their contents |
| `thc_vm_claim` | Claim the complete object extent before scanning its fields |
| `thc_vm_fields` | Declare batches of four-byte slot indices, optionally following their targets |
| `thc_vm_targets` | Trace targets without declaring narrow slots, including targets from wide fields |
| `thc_vm_marked` | Query liveness after draining tracing work |
| `thc_vm_prepare` | Freeze forwarding tables; reject a promotion that will not fit |
| `thc_vm_forward` | Read the frozen destination for root and field repair |
| `thc_vm_finish` | Move, pack metadata, adopt the rings and republish canonical aliases |

Tracing may drain repeatedly in one epoch. Java keepalive and generalized weak
closure can both discover more work. Once preparation succeeds, roots and
external slots are repaired before movement. Neither forwarding table may be
reset while another consumer still needs it.

VM callbacks currently run serially on the registered VM thread. Jam's copy
workers remain parallel. A C callback does not by itself make a native worker
safe to call HotSpot: parallel scanners will need resource areas, thread-local
VM state and reference-discovery queues.

## Scanning objects and roots

The scanner obtains `oop::size()`, claims all of those cells, then enumerates
fields. A declaration-only `DO_FIELDS` pass records narrow slots; a
`DO_DISCOVERY` pass follows strong targets and discovers Java references.
This keeps weak referents relocatable without turning them into strong edges.
Java layouts are fixed, so subsequent reference-list writes use already
declared slots, including slots that initially held null.

Both `do_oop(narrowOop*)` and `do_oop(oop*)` are needed. Wide heap slots are
recorded and repaired by the VM before copying. Stack chunks are transformed
before scanning so their internal derived pointers become relative. Old chunks
require store barriers when resumed threads write new young roots; young TLAB
chunks do not. The VM's GC-mode state separately controls slow thaw and
restoration of derived pointers.

The root walk covers threads and handles, strong `OopStorage`s, class-loader
data and compiled code. Class unloading is disabled, so class-loader data and
code constants remain conservatively strong. Weak `OopStorage`s are cleared
against liveness and their surviving targets are forwarded.

The code-cache pass owns nmethod roots. Adjustment repeats the actual walk with
`NMethodToOopClosure::FixRelocations`; overwriting a saved list of slot addresses
would miss the relocation work. Derived-pointer recording brackets marking,
stops before adjustment, and repairs derived pointers after their base roots.

Mark words move unchanged. That preserves identity hashes and locking state,
while the normal VM synchronizer protocol still controls when moving is legal.
Jam's side metadata supplies forwarding; it does not borrow the object header.

## Remembered sets and pinning

`JamBarrierSet` derives from `ModRefBarrierSet`. Interpreter, C1, C2, Graal,
Unsafe, volatile and arraycopy stores publish exact source locations. Runtime
field stores and atomics preserve their holder; raw array ranges are known
strong slots. The barrier records locations without reading their current target.

A retaining minor replays the sparse set with Java reference policy, keeping
surviving old-to-young entries for subsequent minors. Successful promotion clears
the set. Major marking records it again while visiting old fields, and preparation
relocates those source indices. There is no card table or block-offset table.
Continuation retirement removes stale slots at the existing stack/argument
retirement sites. See [architecture](architecture.md) for the pass boundaries.

JNI critical regions use the pinned JDK's `GCLocker`. `JamHeap::pin_object` and
`unpin_object` enter and leave it. Collection blocks before acquiring the heap
lock and unblocks after releasing it. This prevents movement during a critical
region; it does not provide arbitrary per-object pinning.

## Reference processing

The adapter uses the real `ReferenceProcessor` and `WeakProcessor`, with jam
callbacks for liveness, retention and draining. A hook before Java final
keepalive retains the already-frozen guest finalizer batch. The complete order
is described in [weak pointers](weak-pointers.md#java-references-in-the-same-heap).

Epsilon was useful scaffolding for heap registration, allocation and TLABs.
The historical
[mark-compact example](https://github.com/shipilev/jdk/blob/ae2cf98353d6267d109409b85eba5f5462c20a0b/src/hotspot/share/gc/epsilon/epsilonHeap.cpp)
provided a starting point for safepoints and root walks. Its scalar compactor,
header forwarding and incomplete reference treatment were not the backend.
Java and generalized weak policy both use jam's actual marks and movement.

## Compiler support

Interpreter, C1 and C2 record exact old-to-young source slots. The
[Graal patch](https://github.com/ekmett/thc/blob/main/src/vm/patches/graal-jam.patch) recognizes Jam's exported collector
identity and lowers stores and array copies to the same remembered-slot API.
Both Java Graal and libgraal use that lowering. The VM checks the actual compiler's GC support
before installing Java code, including code returned by a libgraal isolate.
An unmodified compiler cannot silently treat Jam as Serial GC.

Graal can keep derived pointers into objects across a safepoint. After movement,
the collector publishes the new space tops before repairing those pointers, so
promoted bases are valid members of the heap during repair.

The [Native Image adapter](native-image.md) supplies SubstrateVM's allocation
lowering, stack maps, permanent image roots and pinning rules. Both adapters
use the same Jam backend and compressed reference bits.

## Working on the backend

Keep new backend code in C++26 module partitions, one concern per partition.
Use east const, `noexcept`, snake-case names and concepts where a template has
an actual requirement. Public operations need contracts: ownership, phase
preconditions, lifetimes and the laws a caller can depend on.

Hint's attribute catalog is textual. Include `hint.h` in the
global module fragment when using it; macros do not travel through imports.
Use its lifetime, visibility and target annotations where their contracts
apply. ISA dispatch belongs at kernel granularity, with intrinsic wrappers in
the global fragment. There is no reason to reproduce jam's SIMD kernels in
this adapter.

The C header is a deliberate foreign-language boundary. Its declarations must
remain consumable by the JDK's C++14 translation units and C JNI callers. C++26
module types belong behind that boundary. See [the build guide](build.md) for
toolchain selection. The phase contracts are declared in
[`thc_vm.h`](https://github.com/ekmett/thc/blob/main/src/vm/src/adapter/thc_vm.h).

## Truffle leaf returns

HotSpot and Substrate use the same Truffle compiler phases. HotSpot packages
them in libgraal; Native Image embeds the Substrate-targeted compiler. A change
to the shared phase still needs qualification with both target representations.

THC's Graal patch makes the existing `TruffleSafepointInsertionPhase` omit
return polls for proven straight-line scalar leaves. It checks every graph node,
including floating work, and requires the complete fixed chain to run from start
to return. Plain heap reads need a non-null base, no barrier, no ordering effect
and no implicit null check. Calls, loops, allocations, monitors, bulk operations,
writes and unclassified nodes retain the ordinary polling behavior. Eligibility
comes from the lowered operations, not a function name or a non-recursive label.
HotSpot and Substrate pointer compression nodes explicitly promise finite scalar
lowering without calls, loops, allocation, barriers or deoptimization. Unknown
compression implementations make no such promise and retain polling.

`TruffleLoopSafepointEliminationPhase` no longer treats a call as evidence that
the callee will poll before returning. The caller retains its loop poll. Existing
counted-loop proofs remain in place. This changes Truffle thread-local handshake
polling, not HotSpot's GC safepoint policy.
