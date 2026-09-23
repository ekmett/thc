# Thunk updates

An evaluated thunk releases its own references to the entry target and captured
environment. It retains the answer, or a memoized guest exception/runtime fault.
An unexpected host exception resets the thunk for retry and retains the original
entry and environment. Blackhole detection and sharing remain unchanged.

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

The implementation at `b778c0dbb2a776c4ec0b6f32a76db4660ebb82db` passes all 80 tests.
The new tests exercise release on success and memoized failure, retry after host
failure, CAF entry compilation, shared aliases, local updates, and recursive
publication in both backends. Both backends also match the native Map oracle on
18 inputs, before and after compilation, with no unsupported traps.

The [typed-result and tail-cycle measurements](typed-tail.md) predate these thunk
changes. They are not measurements of thunk release or binding writeback.
