# Function labels and explicit C finalizers

GHC 9.14.1 `LitLabel` contains an exact symbol and `FunctionOrData` tag.
The exporter preserves these as `function-addr` and `data-addr` literals with
`AddrRep`. A label carries no argument types, calling convention, or library
ownership certificate. Runtime providers must establish those separately.
The installed Hello World closure contains `libdwPoolRelease` twice and
`backtraceFree` once as function labels; `enabled_capabilities` is a separate
data symbol. The exporter does not parse GHC's printed `__label` syntax.

The selected native RTS has `USE_LIBDW=0`. Its original `LibdwPool.c` and
`Libdw.c` define `libdwPoolRelease` and `backtraceFree` as empty functions with
the ABI `void (void *)`. Their pinned, unchanged source is compiled into
`libdw-unavailable.bc` by the existing C pipeline, using the selected GHC
public headers and original private declarations. Windows also builds a native
`libdw-unavailable.dll` from those same bodies. The build rejects enabled libdw.
This preserves actual function symbols and does not invent a successful DWARF
session or backtrace.

Windows loads the DLL through JDK FFM with native authority and `IOAccess.NONE`.
This avoids Sulong's PE dependency lookup requiring guest filesystem access for
`KERNEL32.dll`. The two empty C bodies satisfy the critical downcall contract:
they do not block, retain pointers, or call Java. Heap access supplies the actual
managed segment only during the call; pinned and context-owned native storage
retain their owner locks and borrows. Unowned numeric addresses, stale storage
and labels belonging to another context reject. Other hosts retain Sulong.

`addCFinalizerToWeak#` has arguments `Addr#, Addr#, Int#, Addr#, Weak#, State#`
and returns `(# State#, Int# #)`. The addresses are the function, object, and
optional environment, in that order around the flag. A zero flag calls
`function(object)`; any nonzero flag calls `function(environment, object)`.
Registration on a live weak returns 1 and prepends the callback; registration
on a dead weak returns 0. The two libdw function signatures have no environment
argument, so their ABI is only suitable for a zero flag. The original `free`
label uses the same ABI and releases a live, context-owned malloc base (or null).
Dead weak registration returns zero before inspecting an already-freed base;
function ownership, weak ownership and the ABI flag are still checked.

Explicit `finalizeWeak#` atomically makes the weak dead and detaches its payload,
then runs C callbacks synchronously in reverse registration order outside the
weak lock. Only after those effects does it return the original Haskell action
and its validity flag. A weak with only C finalizers returns flag 0 after running
them. The Haskell action is never executed by the primitive itself.

The native Haskell fixture checks real non-null function addresses, registration,
dead-registration failure, repeat finalization, and preservation of the separate
Haskell finalizer action. The source fixture exports actual GHC Core and verifies
the function/data distinction, `AddrRep` certificates, and the typed finalizer
primop. THC resolves the two original one-argument libdw labels into
context-owned, nonnumeric native callables, and `free` into the owned allocation
registry. Explicit weak finalization invokes
registered callbacks outside the registry lock. The two selected original
callback bodies are empty: unchanged bytes in the native oracle cannot prove
invocation or callback order. Separate registry instrumentation checks order and
the fact that invocation is outside the lock; the JVM integration check executes
the actual Sulong members or Windows DLL functions. The native fixture producer
supports static Windows GHC and uses canonical paths in its checked manifest.
On Windows, `bin/windows.ps1 -Action LibdwTest` runs the original eight-call
oracle, eighteen C-finalizer checks, genuine function/data label export and both
JVM handoff forks. Both backends retain first-compiled-call checks. Storage,
context, native authority and expired-owner controls run with `IOAccess.NONE`.
The Linux-only owned `free` test verifies release, expired aliases and dead
registration on both compiled backends; it remains skipped on Windows.
Automatic guest GC finalization
and arbitrary C function labels remain unsupported. The separate
`enabled_capabilities` data label exposes a read-only live Word32 cell.

Package-owned one-address finalizers use a separate typed admission path. The
exporter retains the actual stock `CLabel` declaration, its declared and
normalized nominal types, and the unchanged foreign product. A normalized
`FunPtr (Ptr a -> IO ())` proves a candidate ABI; it does not by itself make the
label executable. Acquisition must retain the original C definition with an
exact `void(pointer)` ABI and root its namespaced adapter in a completely linked
component. These declarations and roots use schema-two inner provenance records;
ordinary schema-one call records and the CBD container framing remain unchanged.

Runtime labels retain that component and owning context. Cross-context, closed
component, ambiguous-label, malformed signature and disposed-allocation uses
reject. Native allocation borrows cover callback execution, including known
returned aliases. Registration and explicit finalization keep the existing
DEAD-before-call, newest-first and no-replay rules. The reserved `free` label
still uses checked allocation ownership; package labels do not acquire that
special deallocation authority. Automatic GC finalization and arbitrary function
pointer calls remain unsupported.

The native zlib dependency profile currently supports Linux x86-64 LP64. It
validates the original LLVM declarations before linking libz, preserving its
integer widths, version/size checks and real library pointer results. This is a
native library dependency, not a Java z_stream implementation.

Package calls and typed package finalizers can project pointer-bearing pinned
arrays into that same native storage. The boundary validates the complete
transitive pointer-cell graph before writing native encodings, retains aliases
and native allocation borrows, and reconciles native writes even when a call
throws. Native-created pointers retain the originating context/library rather
than an expired argument-view lease. Explicit typed address reads recover newly
written pointer fields; the runtime does not scan scalar fields for pointers.
Moving buffers and opaque guest objects cannot be embedded as native pointers,
and raw byte exposure of a pointer-bearing allocation remains rejected. This
does not add native-to-guest callback support or automatic GC finalization.

Primary implementations at the pinned GHC revision:

- [Weak primops](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/PrimOps.cmm#L827)
- [C callback order and environment](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Weak.c#L29)
- [Disabled pool release](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/LibdwPool.c#L57)
- [Disabled backtrace release](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Libdw.c#L428)
