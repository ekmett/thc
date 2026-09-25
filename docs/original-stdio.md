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
| `fdReady` | ccall / safe or unsafe | Int32Rep, Word8Rep, Int64Rep, Word8Rep | Int32Rep |
| `ghczuwrapperZC1ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuSET` | capi / unsafe | none | Int32Rep |
| `ghczuwrapperZC2ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuCUR` | capi / unsafe | none | Int32Rep |
| `ghczuwrapperZC0ZCghczminternalZCGHCziInternalziSystemziPosixziInternalsZCSEEKzuEND` | capi / unsafe | none | Int32Rep |

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
clang and C headers. It records actual errno/SEEK values and checks byte, pointer,
CInt, CBool, size_t and ssize_t widths. The runtime checks the generated resource's
platform and LP64 widths before foreign effects. This probe does not read or hash
installed GHC files, mutate the process locale, or require native access when
executing the managed writes. Supported build targets are the existing Linux GNU
and Darwin x86_64/aarch64 host targets; cross-target resources are rejected.

The errno slot belongs to a context and Java thread, preserving the existing guest
thread identity. Supporting safe/unsafe descriptors does not make these foreign
operations asynchronously interruptible or establish migratable IO scheduling.

## Original seek constants

The three exact original capi wrappers consume State and return State/Int32;
they are not raw numeric literals, symbol-pattern aliases, or arbitrary native
constant imports. The C probe supplies SEEK_SET/CUR/END, each a distinct signed
CInt. ManagedStdio translates these values to the separate private managed
absolute/relative/end modes before lseek; it does not assume native constants
are 0/1/2. Queries and successful seeks preserve sticky errno, and State is
checked before either operation. The same typed bytecode status instruction is
specialized for the constant operation; no generic boxed foreign dispatcher is
introduced.

The existing `original-stdio-seek` Haskell fixture imports the genuine installed
sEEK_SET/CUR/END declarations and c_lseek directly. Its private mode selectors
exercise those original wrappers with 24 native observations, including tell,
EOF, closed/bad descriptors, wide/negative offsets and a nonseekable pipe.
Kotlin compares both Core stages/backends, inlining modes and first installed
compiled entries; synthetic ABI negatives remain separate from source proof.
This does not establish original Handle execution, general native FFI, or
foreign-stub linkage.

## Bounded original file readiness

The exact original `fdReady` signature uses signed CInt, unsigned one-byte CBool,
signed Int64 milliseconds, CBool, and State. Lowered and stored operands must
retain those proofs; scalar carrier sharing cannot relabel an address or State.
Both boolean values must be canonical zero or one before any foreign effect.

Context-owned regular files return one for read and write readiness, including
EOF and an incompatible open mode. Closed or unknown nonnegative descriptors
also return one: GHC's POSIX implementation treats any positive `poll` result as
ready, including `POLLNVAL`; the subsequent IO operation reports the bad descriptor.
Negative descriptors return zero only for a zero timeout. Other negative-descriptor
waits fail explicitly until an interruptible waiting service exists. Readiness
does not change the file position or clear sticky errno. POSIX ignores `isSock`.

Embedding streams have no readiness contract and return minus one with host
`ENOTSUP`. THC neither polls a process descriptor nor infers readiness from an
InputStream's available-byte count. This bounded behavior is not general Handle,
socket, pipe, scheduler, or asynchronous IO support.

`cabal run exe:thc-fixtures -- original-fd-ready` prepares 168 native observations
and an explicitly synthetic, GHC-typed scalar consumer of actual installed FD
FCallIds. Replacement of a template foreign head requires GHC type equality and
retains the original unit, symbol, convention, safety, and representation metadata.
The fixture retains original declaration call records, template, and adapted
exports; it does not claim unchanged original FD/Handle execution. GHC's ordinary
typed declaration unfoldings supply the original foreign Ids even in stock thin
interfaces. This is a fixture-only, non-executable declaration projection, not a
production fallback for missing complete Core. Native comparison uses the actual
linked GHC C symbol.

This slice alone is not ordinary `putStrLn`/Handle support. Unchanged stdout
initialization additionally needs terminal/locale capability calls, and the
original write path queries readiness before choosing a safe or unsafe call.
An arbitrary embedding OutputStream has no readiness protocol: never return
unconditional readiness or poll process fd1 for it. Original error-string
construction and standard-handle shutdown flushing are also separate work.
Regular-file open/stat/locking, descriptors from other processes, arbitrary native
pointer buffers, and generic Sulong symbol interposition are not established here.
