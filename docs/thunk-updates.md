# Thunk updates

An evaluated thunk releases its own references to the entry target and captured
environment. It retains the answer, or a memoized guest exception/runtime fault.
An unexpected host unwind leaves the update interrupted; it never resets the
thunk and replays possible effects. Later demand fails if no resumable
continuation was captured. Supported async capture instead parks a continuation
that a later owner can resume through the same update protocol. Blackhole
detection and sharing remain unchanged.

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
