# Original GHC stdio foreign boundary

This bounded LP64 bridge consumes the original GHC 9.14.1 foreign-call
descriptors, including copies inlined into otherwise unchanged consumers. It
does not replace `GHC.Internal.IO.FD` or require new `thc_io_v1` imports.

The initial contracts are the exact `ghc-internal` static function targets:

| Target | Convention / safety | Arguments before State | Result after State |
| --- | --- | --- | --- |
| `ghczuwrapperZC20ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite` | capi / safe | Int32Rep, AddrRep, Word64Rep | Int64Rep |
| `ghczuwrapperZC21ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCwrite` | capi / unsafe | Int32Rep, AddrRep, Word64Rep | Int64Rep |
| `__hscore_get_errno` | ccall / unsafe | none | Int32Rep |

Matching is independent of the consuming binding's name. These are exact pinned
symbols, not a rule accepting arbitrary generated wrapper names. CInt, size_t,
ssize_t, and machine Int retain their distinct representation proofs even though
the JVM stores their supported forms in longs. State is checked before effects.

The implementation routes writes to context-owned descriptors and the embedding's
streams; descriptor1 is not process fd1. It preserves offsets, binary bytes,
zero-length calls, partial counts, and sticky errno across successful operations.
Operational errors use actual host C errno constants. Invalid managed addresses,
out-of-range memory requests, and noncanonical CInt values remain runtime faults.
A nonempty zero-progress transport result is reported as EIO, so the original
Haskell write loop cannot spin indefinitely on an unsupported transport behavior.

`generateStdioAbi` compiles and executes a small C probe using the selected host
clang and C headers. It records actual errno values and checks byte, pointer,
CInt, size_t and ssize_t widths. The runtime checks the generated resource's
platform and LP64 widths before foreign effects. This probe does not read or hash
installed GHC files, mutate the process locale, or require native access when
executing the managed writes. Supported build targets are the existing Linux GNU
and Darwin x86_64/aarch64 host targets; cross-target resources are rejected.

The current errno slot belongs to a context and host thread, which matches the
current synchronous guest execution model. It must move into logical guest-thread
state before migratable/resumable scheduling. Supporting safe/unsafe descriptors
does not implement Haskell asynchronous interruption or `throwTo` resumability.

This slice alone is not ordinary `putStrLn`/Handle support. Unchanged stdout
initialization additionally needs terminal/locale capability calls, and the
original write path queries readiness before choosing a safe or unsafe call.
An arbitrary embedding OutputStream has no readiness protocol: never return
unconditional readiness or poll process fd1 for it. Original error-string
construction and standard-handle shutdown flushing are also separate work.
Regular-file open/stat/locking, descriptors from other processes, arbitrary native
pointer buffers, and generic Sulong symbol interposition are not established here.
