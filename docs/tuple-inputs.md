# Typed unboxed tuple inputs

Both backends accept ordinary guest parameters with exact recursive unboxed tuple
proofs. A tuple contributes one logical parameter, while its concrete Long, Float,
Double and known reference leaves occupy separate typed fields and frame locals.
Nested tuple boundaries remain part of the proof. State#/Proxy# components and
empty tuples contribute no payload slots, but their argument expressions still
execute in source order before a call or partial application is published.

Genuine GHC pre/post export of ordinary tuple inputs, tuple PAP prefixes and
overapplication remains unqualified. Synthetic fixture-free controls cover the
runtime transport contracts.

Boxed tuples remain DataValue references. An unlifted boxed datatype remains one
reference. Neither is flattened. A lifted leaf inside an unboxed tuple stays lazy,
including when the tuple parameter is strict or unused. `BoxedRep Nothing` also
has a known traced reference carrier: unknown levity does not introduce a force
or establish evaluatedness. Its proof can refine to either concrete boxed levity;
conflicting concrete levities still reject. Unknown runtime layouts are excluded;
exact vector leaves retain their fixed species. Supported sums may
occur inside recursive tuples: their physical tag/payload slots expand at that
logical component without changing neighbouring offsets. An exact evaluated
`AddrRep` leaf carries only a checked `ManagedAddress`, never a native pointer.
Nonrecursive unlifted tuple/sum lets evaluate their right-hand side once into
typed frame locals, even when unused. Recursive or lifted aggregate lets and
global aggregate storage remain rejected. The [Core host ABI](site/embedding.md#load-a-core-entry)
uses recursive logical arrays for supported aggregate arguments and results.
[Tuple closure/thunk captures](tuple-captures.md) preserve owned
physical fields and exact logical nesting. [Local tuple-join arguments](tuple-joins.md)
use the same logical layouts but parallel moves within the current frame.
Saturated boxed constructors support [owned tuple/sum fields](aggregate-heap-fields.md).
Ordinary sum parameters and captures use [the existing typed input protocol](sum-inputs.md).
Scalar Float#, Double# and Addr# parameters also select this typed input protocol,
without needing an aggregate argument. Floating values occupy primitive fields;
addresses retain their checked ManagedAddress reference and owner. Narrow integer
inputs retain their exact-width fields. Other scalar-only and exact-empty-only
calls retain their existing conventions; this selection is independent of the
optional dense scalar handoff mode.

A typed call passes a single precise generated storage object in the outer Truffle
argument array. Tuple primitive leaves never become Object[] payload elements.
The callee copies the fields into its own typed locals and releases the input
before guest continuation. Input and result storage have independent ownership;
an outstanding result cannot be overwritten by preparing the next call.

Compiled callers create fresh typed storage that can disappear with an inlined
callee. A residual compiled edge may allocate that carrier and its one-element
outer packet. Interpreter calls reuse input loans. Durable partial-application
prefixes own separate typed storage, retain logical supplied counts and never
borrow a reusable loan. PAP suffixes, overapplication, direct/PIC/generic dispatch,
and tail restoration use the same recursive logical layout and physical offsets.

Ownership is recorded on each carrier, so a fresh object materialized by
deoptimization is never released as a pool loan. Tail transfers distinguish
materialized transfer storage from fresh direct ingress. Generation checks prevent
an older caller's cleanup from releasing storage reused by a later call. Generic
layout cleanup and copy helpers receive metadata and storage, never a VirtualFrame.
Cached call shapes keep constant field accesses in the common path.
An existing scalar operand without an exact primitive proof can generalize its
bytecode local to Object when one higher-order site changes numeric targets.
That scalar position uses a generic local read and a checked target-kind cast;
exact tuple leaves continue to use primitive accessors.
