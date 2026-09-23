# Typed unboxed tuple inputs

Both backends accept ordinary guest parameters with exact recursive unboxed tuple
proofs. A tuple contributes one logical parameter, while its concrete Long, Float,
Double and known reference leaves occupy separate typed fields and frame locals.
Nested tuple boundaries remain part of the proof. State#/Proxy# components and
empty tuples contribute no payload slots, but their argument expressions still
execute in source order before a call or partial application is published.

Boxed tuples remain DataValue references. An unlifted boxed datatype remains one
reference. Neither is flattened. A lifted leaf inside an unboxed tuple stays lazy,
including when the tuple parameter is strict or unused. Unknown layouts or levity,
sums, vectors and addresses are excluded from this tuple-input slice. Aggregate
captures, heap fields, ordinary let bindings and local-join arguments/captures are
still rejected, as are aggregate parameters and results at the public host entry.
Scalar-only and exact-empty-only calls retain their existing conventions.

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

`TupleInputAudit.hs` supplies genuine pre/post-Tidy boundaries and 139 unary plus
seven independent-pair native rows. Its preparer checks separate wraparound
formulas, retained PAP/overapplication paths, recursive shapes and strict audits.
Instrumented JVM tests require exact source-derived compiled guest-entry counts
for every measured row, unchanged active targets, valid host/original/active
compilations, and empty input/result pools. The matrix covers both backends,
inlining enabled/disabled, and default/dense-handoff modes. Additional protocol
controls cover three-target and prefixed cycles, cyclic overapplication returning
an intermediate closure, zero-width effects, cold failures and reusable lazy PAPs.

These tests establish execution and lifetime correctness. Graph controls separately
account for carrier/packet allocation and field traffic; typed fields alone are
not a hardware register-passing claim. A scalar Object-return box can remain at
the public Truffle boundary even when all tuple transport disappears.
