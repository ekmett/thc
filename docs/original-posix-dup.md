# Original descriptor duplication

The pinned GHC 9.14.1 `GHC.Internal.System.Posix.Internals.c_dup` and
`c_dup2` declarations use their original `ccall unsafe` signatures:
`Int32Rep, State# -> (# State#, Int32Rep #)` and
`Int32Rep, Int32Rep, State# -> (# State#, Int32Rep #)` respectively.
Only these exact raw declarations are recognized. The existing Haskell fixture
framework imports them without new FFI declarations and exports both Core stages.

The descriptor namespace belongs to one THC context, not the host process.
Aliases share a channel/stream, offset, append/read/write status, IO monitor and
open-admission claim. Each descriptor can close independently. The last reference
retires the owned channel once; embedding streams are flushed but never closed.
Claims remain visible until retirement callbacks return, including failure paths.
These internal claims are not GHC RTS `lockFile`/`unlockFile` bookkeeping.

`dup` chooses the lowest free descriptor, including 0, 1 and 2. `dup2` validates
its source first, atomically installs the target, and silently discards an old
target's `IOException` on close. A valid same-fd call is a no-op; an invalid source
does not alter the target. A provider's unchecked exception remains a runtime
failure, without rolling back the already-installed replacement. Registry locks
are never held while invoking a provider or waiting for a shared IO monitor.

The sparse namespace is 0 through `Int.MAX_VALUE`, independent of host
`RLIMIT_NOFILE`, native descriptor flags, or host descriptor numbers. Exhaustion
reports the C compiler's actual `EMFILE`; a small injected service limit tests
that boundary deterministically. Invalid signed-CInt descriptors report `EBADF`.
A noncanonical JVM `Long` carrier is a runtime fault before effects. Success
preserves the original errno slot. The private `thc_io_v1_open` API retains its
monotonic, non-reusing fd>=3 allocation; it skips occupied dup2 targets.

The native oracle observes shared offsets, independent close, append status,
lowest-free stdio slots, replacement, self/alias replacement and invalid calls.
Kotlin compares roles and bytes, never equating native and context fd numbers.
It also checks malformed proofs/State and explicitly installs both active entry
targets before checking every first and subsequent compiled invocation.

This is not full original GHC FD/Handle support: `unlockFile`, authoritative
opened-resource `fstat`, host descriptor import, scheduler-integrated blocking,
weak/finalizer ownership and full `putStrLn` remain separate obligations.
