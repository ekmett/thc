# Typed unboxed sum results

Both backends execute saturated unboxed sum constructors with two or more
alternatives, guest function results, local join results, forwarding and immediate
cases. [Ordinary inputs and
captures](sum-inputs.md) share the same layout. The exact logical alternatives remain
separate from GHC's physical `primReps`, `tagSlot` and `alternativeSlots` evidence.
Boxed `Either`, ordinary boxed tuples and unlifted boxed references keep their
ordinary one-reference representation; none becomes an unboxed sum by name,
arity or liftedness.

Fixture-free controls in `UnknownBoxedSumTest` cover mixed lazy-reference and
integer results through zero-arity and recursive local joins, inactive-reference
clearing in a reused destination, released loans and the first installed call in
both backends. Conflicting join/lambda result proofs reject before execution.
Genuine GHC-exported sum-result joins and transfers from `runRW#` continuations
remain unqualified.

Supported leaves include machine and fixed-width integral representations,
`FloatRep`, `DoubleRep`, evaluated managed `AddrRep`, supported exact vector
species, boxed references with known or unknown levity, and scalar void tokens
such as `State#`. Concrete tuple and sum payloads nest recursively. Integral
payloads share canonical JVM Long slots; narrow 8/16/32-bit payloads widen from
`Int` on construction and narrow only in the selected alternative. Float and
Double retain separate slots. Lifted, unlifted and unknown-levity pointers share
traced JVM slots by their order within each alternative, preserving repeated
fields without adding a WHNF guarantee. Managed addresses use separate traced
slots from integral bits, and vectors retain their exact species.

Concrete GHC `primReps` and `alternativeSlots` remain exact native evidence,
including distinct lifted/unlifted pointer slots and native address/word sharing.
JVM projections are derived from the recursive logical shape independently of
those native slots. A known-pointer `BoxedRep Nothing` payload leaves the native
sum layout `null`; the complete logical alternatives still determine JVM storage.
The runtime validates native evidence and computes JVM placement before lowering.
It also checks
constructor family arity, one-based tags, payload shape and levity, case binder
shape, alternative binders and retained result proofs in every arm, including
cold arms. Scalar `State#`, `(# #)` and `(# State# #)` stay distinct logical types
even when they need no payload storage.

A selected zero-width payload still executes; it may throw. Lifted and
unknown-levity references are stored without forcing them, including bottom.
Constructors clear every inactive destination slot before evaluating the selected
payload and write the tag only after successful evaluation. Cases check the tag
and project typed caller-frame slots according to the selected alternative.
The raw Long tag is validated against the exact family arity before selecting
an explicit arm or DEFAULT. AST cases retain an immutable tag-to-arm map,
branch profiles and explicit primitive execution overrides; bytecode uses an
immutable arity operand, typed locals and conditional control flow.

Results use the existing [typed tuple completion protocol](tuple-results.md).
A callee first computes its result in typed local slots. An inlined callee may
finish into fresh virtualizable typed storage; an actual residual root finishes
into a reusable, separately owned output slab. Each caller copies the physical
slots into its own frame and releases any pooled result before guest continuation.
Tail forwarding uses the same canonical copy/finish path. Full logical signatures
are checked even when two shapes share a physical storage layout. Failure during
copy releases and clears the actual loan. A fresh carrier materialized by deopt
remains unpooled and never releases a nonexistent loan. No sum `DataValue`, boxed
payload array, or escaping guest frame is introduced.

Local join results use the same exact sum shape, but stay within one activation:
the AST copies typed tag/payload region slots to the enclosing destination and
clears its private scratch slots; bytecode writes directly to that destination.
Recursive backedges and zero-arity joins do not allocate sum closures or result
loans. An outer sum-returning join is a lexical control target, not a captured
sum value. [Sum join inputs and captures](sum-inputs.md) use the same typed
frame slots, including parallel recursive transfers.

Tuples may recursively contain supported sums, preserving logical nesting,
zero-width State/empty-tuple distinctions and lazy lifted neighbours. Inactive
boxed reference fields use null; inactive address fields use the managed null
address, and inactive vector fields use a zero of the exact species. These are
THC padding values, never observations of native inactive registers.
A missing native layout is accepted only when known-pointer unknown levity,
possibly in a nested sum, leaves every logical component and JVM carrier known.
Genuinely unknown RuntimeRep payloads and incomplete logical shapes still reject.
Partial sum constructors and recursive or lifted sum lets
also remain unsupported; nonrecursive unlifted sum lets use typed locals.
The [Core host ABI](site/embedding.md#load-a-core-entry) transports supported
sum arguments/results as `[tag, payload]` arrays and can return callable
context-owned functions with sum signatures. Top-level sum storage is rejected in
both strict and diagnostic mode; diagnostic mode does not invent a heap carrier.
Unsupported cold function paths retain the existing diagnostic trap policy.
Exact sum fields in saturated boxed constructors are supported separately through
[owned aggregate heap storage](aggregate-heap-fields.md), retaining their tag and
payload projections. Ordinary sum function parameters are described [separately](sum-inputs.md).
