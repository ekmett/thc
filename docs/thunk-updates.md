# Thunk updates

An evaluated thunk releases its own references to the entry target and captured
environment. It retains the answer, or a memoized guest exception/runtime fault.
An unexpected host unwind leaves the update interrupted; it never resets the
thunk and replays possible effects. Later demand fails if no resumable
continuation was captured. Supported async capture instead parks a continuation
that a later owner can resume through the same update protocol. Blackhole
detection and sharing remain unchanged.

Ownership transitions serialize on the thunk monitor. The owner writes the
answer, failure or saved continuation before releasing its state; readers
acquire that state before inspecting the payload. Nonforcing weak-key resolution
uses the same publication boundary without claiming or evaluating the thunk.

Forcing a known local binding also replaces that binding's thunk reference with
the answer. A subsequent read of the same binding can therefore skip the thunk's
state machine. Separate aliases still reach the memoized answer through the
original thunk. Both AST and bytecode perform this update only after successful
forcing and only while the binding still contains the original thunk.

Recursive groups use shared initialization cells. Updating a forced recursive
binding changes the cell's contents rather than replacing the cell, so closures
that captured it before the group's publication continue to see the shared
binding. Immutable constructor fields and capture properties are not rewritten;
a restored local binding can still be updated.

These changes remove references held by the thunk itself. Call caches, program
roots, and exception locations may independently retain executable code. The
memoized answer is still an Object field: an escaping thunk holding a machine
integer can still retain a boxed Long.

## Nonforcing lifted values

`DataValue`, `Closure` (including PAPs), `ForeignValue` and `Thunk` implement
Jam's language-owned `Lifted` interface. `resolve()` observes an already
published replacement; it does not execute, resume, claim or wait for a thunk.
Only a successful WHNF update exposes an answer. Null means no replacement is
available, so THC still uses its own thunk state to distinguish an unresolved
computation from a terminal value.

`project(n)` uses zero-based logical constructor-field numbers and returns an
existing lifted reference, including an unevaluated field. It does not box
primitive fields or synthesize tuple, sum or vector carriers. Such fields, and
unavailable or out-of-range projections, return null. Constructor layouts keep
their existing final primitive and reference storage; `I#` already contains a
primitive integer and needs no second representation.

`LiftedValues.resolveBoxed` follows available answers without allocating a
visited set. It preserves the original identity on cycles, has no chain-depth
limit, and preserves existing raw Object answers at boxed runtime boundaries.
Weak registration and bootstrap handoff share this traversal. None of these
operations changes the ordinary forcing, sharing or continuation protocol.
Unevaluated selector bodies remain computations until evaluated; this interface
does not add selector recognition or collector-time rewriting of strong slots.

Lazy foreign observation uses the existing context-owned
[polyglot boundary](polyglot.md#lazy-haskell-values), preserving the raw guest
carrier and its owner checks.
