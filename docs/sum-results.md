# Typed binary unboxed sum results

Both backends execute saturated binary unboxed sum constructors, guest function
results, forwarding and immediate cases. The exact logical alternatives remain
separate from GHC's physical `primReps`, `tagSlot` and `alternativeSlots` evidence.
Boxed `Either`, ordinary boxed tuples and unlifted boxed references keep their
ordinary one-reference representation; none becomes an unboxed sum by name,
arity or liftedness.

The first slice accepts machine `IntRep`/`WordRep`, `FloatRep`, `DoubleRep`, known
lifted or unlifted reference leaves, scalar void tokens such as `State#`, and
exact recursive tuple payloads composed of those leaves. Int and Word share
one machine-word storage class without changing bits. Float and Double slots,
and lifted and unlifted reference slots, remain separate. The runtime recomputes
and checks the complete binary placement map before lowering. It also checks
constructor family arity, one-based tags, payload shape and levity, case binder
shape, alternative binders and retained result proofs in every arm, including
cold arms. Scalar `State#`, `(# #)` and `(# State# #)` stay distinct logical types
even when they need no payload storage.

A selected zero-width payload still executes; it may throw. A lifted reference
is stored without forcing it, including when the payload itself is bottom.
Constructors clear every inactive destination slot before evaluating the selected
payload and write the tag only after successful evaluation. Cases check the tag
and project typed caller-frame slots according to the selected alternative.
AST cases retain branch profiles and explicit primitive execution overrides;
bytecode uses typed locals and conditional control flow.

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

Nested sums, tuples containing sums, nonbinary sums, integer-width conversion,
address/vector leaves, `BoxedRep Nothing` and unknown logical or physical layouts
remain unsupported. Sum arguments, partial sum constructors, captured sums,
ordinary sum let bindings, heap fields, join arguments/results/captures and
public host sum results also remain unsupported. Function values returning sums
may still pass through existing scalar/reference closure paths, but a sum value
cannot cross those excluded boundaries. Top-level sum storage is rejected in
both strict and diagnostic mode; diagnostic mode does not invent a heap carrier.
Unsupported cold function paths retain the existing diagnostic trap policy.

`check-sum-layout.py --prepare` retains 17 layout families and 130 native/model
rows. Six retained-sum consumers plus a GHC-eliminated scalar control are accepted;
all other roots remain explicit frontiers. `prepare-sum-result-audit.py` adds
110 fresh native unary rows and seven independent input pairs, checks independent
wraparound formulas, and strict-audits all 12 scalar entry roots before and after
Tidy. The original `AggregateFrontier` sumPayload, sumZeroLazy and coldSum
consumers now also execute their 24 native rows through the same strict gates. It retains opaque producer boundaries, tuple payloads, lazy lifted bottoms,
State/empty effects, two outstanding results, direct exceptions and self/mutual
forwarding. Both preparations record source, exporter, auditor, toolchain, native
and Core hashes; JVM tests reject stale evidence.

`SumResultTest` checks native values before compilation and then requires exact
compiled guest-entry counts, original/active/host validity and unchanged active
call targets for every measured successful row, with normal and disabled inlining
on both backends and both export stages. Cold exceptions are checked separately
without recompiling or retrying the measured rows. Protocol tests additionally
check inactive reference clearing, release on a shape mismatch, lazy pointer
identity and actual deopt materialization between completion and consumption.
These correctness controls are distinct from generated-code evidence; typed
storage alone does not establish register passing or eliminated allocations.
