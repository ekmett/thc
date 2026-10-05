# Function labels and C finalizers

GHC 9.14.1 `LitLabel` contains an exact symbol and `FunctionOrData` tag.
The exporter preserves these as `function-addr` and `data-addr` literals with
`AddrRep`. A label carries no argument types, calling convention, or library
ownership certificate. Runtime providers must establish those separately.
The exporter does not parse GHC's printed `__label` syntax.

`addCFinalizerToWeak#` has arguments `Addr#, Addr#, Int#, Addr#, Weak#, State#`
and returns `(# State#, Int# #)`. The addresses are the function, object, and
optional environment, in that order around the flag. A zero flag calls
`function(object)`; any nonzero flag calls `function(environment, object)`.
Registration on a live weak returns 1 and prepends the callback; registration
on a dead weak returns 0. The original `free` label uses `void (void *)`,
requires a zero flag and releases a live, context-owned malloc base (or null).
Dead weak registration returns zero before inspecting an already-freed base;
function ownership, weak ownership and the ABI flag are still checked.

Explicit `finalizeWeak#` atomically makes the weak dead and detaches its payload,
then runs C callbacks synchronously in reverse registration order outside the
weak lock. Only after those effects does it return the original Haskell action
and its validity flag. A weak with only C finalizers returns flag 0 after running
them. The Haskell action is never executed by the primitive itself.

A single canonical current-context `free` on an actionless registration with
identical key/value carriers, or a raw managed `MutVar#` or `MVar#` key, has a
[automatic malloc retirement path](weak-explicit.md). Only a direct owned
malloc base or null qualifies. The registration and JDK Cleaner action weakly
reference the Owner; neither retains the address, function provider, context or
guest payload. JVM collection can trigger the paired raw native free without a
managed GC request. Busy borrows or existing free/realloc reservations latch
pending work, retried at completion without blocking or another collection.
Explicitly retired owners consume stale tokens without replay; explicit
free/finalization preserve freed-alias errors. A second callback disarms cleanup
and promotes the exact key/value to strong ownership with newest-first order.
Context close disarms registrations and disposes remaining allocations. There
is no abandoned-context reclamation guarantee. Arena-close/downcall failures
are retained and terminal as described in the weak guide; Windows LocalFree and
arbitrary Haskell/package callbacks remain outside automatic retirement.

Package-owned one-address finalizers use a separate typed admission path. The
exporter retains the actual stock `CLabel` declaration, its declared and
normalized nominal types, and the unchanged foreign product. A normalized
`FunPtr (Ptr a -> IO ())` proves a candidate ABI; it does not by itself make the
label executable. Acquisition must retain the original C definition with an
exact `void(pointer)` ABI and root its namespaced adapter in a completely linked
component.

Runtime labels retain that component and owning context. Cross-context, closed
component, ambiguous-label, malformed signature and disposed-allocation uses
reject. Native allocation borrows cover callback execution, including known
returned aliases. Registration and explicit finalization keep the existing
DEAD-before-call, newest-first and no-replay rules. The reserved `free` label
still uses checked allocation ownership; package labels do not acquire that
special deallocation authority. Automatic Haskell/package finalization and
arbitrary function pointer calls remain unsupported.

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
does not add native-to-guest callback support or automatic package finalization.

Primary implementations at the pinned GHC revision:

- [Weak primops](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/PrimOps.cmm#L827)
- [C callback order and environment](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Weak.c#L29)
