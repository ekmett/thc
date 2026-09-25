# Original caller-owned signal-set images

This Linux x86-64 slice implements exactly the original GHC 9.14.1 unsafe CAPI
wrappers for `sigemptyset` and `sigaddset` in `GHC.Internal.System.Posix.Internals`.
Both return the original `(# State#, Int32# #)`; operands retain exact Addr#,
optional Int32#, and zero-width State# proofs. Direct libc names, arbitrary
wrapper aliases, `sigprocmask`, terminal syscalls and saved-terminal state are
not admitted by this slice.

A native C probe measures the complete `sigset_t` size, alignment, empty-set
write-byte table, per-signal bit positions and invalid-signal errno. It verifies
two nonzero initial patterns, idempotent bit addition, untouched bytes and sticky
errno on success, and returns no resource if the operation cannot be represented
by this closed model. Reserved signal numbers may be holes in the table; they
must not be guessed from NSIG. On the measured Linux host, signals 32/33 fail,
and emptying changes eight bytes while preserving 120 padding bytes.

The runtime checks the complete writable, pointer-free image before effects,
holding its managed allocation monitor across preflight and byte mutation.
Noncanonical signed-CInt carriers fault without mutation or errno changes;
canonical but unsupported signal numbers return -1, leave the image unchanged,
and update the context's existing guest-thread errno slot. Success preserves
that slot. No host process/thread signal mask is read or changed, and no native
pointer, allocation ownership or terminal descriptor is manufactured.

`thc-fixtures original-sigset` imports unchanged installed declarations, produces
genuine optimized pre/post Core and four strict accepted audits, and runs native
GHC IO observations. The 532 rows cover four fill patterns, both operations,
signals 0..128 and signed-CInt extremes, preserving full images and canaries.
The runtime checks both AST and bytecode before and at the first installed
compiled entry, exact two-target/two-entry execution, idempotence, padding,
status/errno parity, malformed stored proofs and handoff release. No source
rewriting, settling, recompilation, mask mutation or whole-IO success is implied.
