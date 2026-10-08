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
Function ownership, weak ownership, the ABI flag and reserved free target are
checked at admission.

Explicit `finalizeWeak#` atomically makes the weak dead and detaches its payload,
then runs C callbacks synchronously in reverse registration order outside the
weak lock. Only after those effects does it return the original Haskell action
and its validity flag. A weak with only C finalizers returns flag 0 after running
them. The Haskell action is never executed by the primitive itself.

The current Jam weak draft retains callbacks as conditional finalizer state
for arbitrary boxed keys. Adding another callback preserves the same conditional
association and newest-first order. There is no key-class promotion or JDK
Cleaner path. Automatic claims dispatch through the owning context's real guest
carrier before the original Haskell finalizer action; guest thread permission
is required. General automatic Haskell/package execution remains unqualified;
see [the weak guide](weak-explicit.md) for current evidence and failures.

The reserved owned `free` uses the existing allocation retirement latch. Busy
borrows or free/realloc reservations defer retirement until completion without
blocking or requiring another collection. Already retired owners consume stale
automatic tokens without replay; ordinary explicit free preserves freed-alias
errors. Callback captures and native borrows cover their actual use; C captures
are cleared before the Haskell action can suspend. Context shutdown joins
admitted guest carriers before disposing providers and remaining allocations.
Uncertain native effects remain terminal and are not replayed. This does not
promise reclamation of an abandoned, unclosed context.

Package-owned C finalizers use a separate typed admission path. The
exporter retains the actual stock `CLabel` declaration, its declared and
normalized nominal types, and the unchanged foreign product. A normalized
`FunPtr (Ptr a -> IO ())` or `FunPtr (Ptr env -> Ptr a -> IO ())` proves the
candidate argument list; it does not by itself make the label executable.
Acquisition must retain the original C definition with the matching exact
`void(pointer)` or `void(pointer, pointer)` ABI and root its namespaced adapter
in a completely linked component. The environment flag must select that declared
arity. Zero ignores the environment; every nonzero value passes it first.

Runtime labels retain that component and owning context. Cross-context, closed
component, ambiguous-label, malformed signature and disposed-allocation uses
reject. Both arguments use the existing typed address transport, including opaque
stable-pointer tokens. Native allocation borrows cover both arguments and their
transitive pointer-cell graphs throughout callback execution, including known
returned aliases. Registration and explicit finalization keep the existing
DEAD-before-call, newest-first and no-replay rules. The reserved `free` label
still uses checked allocation ownership; package labels do not acquire that
special deallocation authority. The draft automatic route uses this same
admission and transport; its public Haskell/package qualification is pending.
Arbitrary function pointer calls remain unsupported.

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
does not add native-to-guest callback support or qualify automatic package
finalization.

Primary implementations at the pinned GHC revision:

- [Weak primops](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/PrimOps.cmm#L827)
- [C callback order and environment](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Weak.c#L29)
