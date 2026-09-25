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
public headers and original private declarations. The build rejects enabled
libdw. This preserves actual function symbols and does not invent a successful
DWARF session or backtrace.

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
context-owned, nonnumeric Sulong callables, and `free` into the owned allocation
registry. Explicit weak finalization invokes
registered callbacks outside the registry lock. The two selected original
callback bodies are empty: unchanged bytes in the native oracle cannot prove
invocation or callback order. Separate registry instrumentation checks order and
the fact that invocation is outside the lock; the JVM integration check executes
the actual Sulong members. The owned `free` test verifies release, expired aliases
and dead registration on both compiled backends. Automatic guest GC finalization
and arbitrary C function labels remain unsupported. The separate
`enabled_capabilities` data label exposes a read-only live Word32 cell.

Primary implementations at the pinned GHC revision:

- [Weak primops](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/PrimOps.cmm#L827)
- [C callback order and environment](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Weak.c#L29)
- [Disabled pool release](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/LibdwPool.c#L57)
- [Disabled backtrace release](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Libdw.c#L428)
