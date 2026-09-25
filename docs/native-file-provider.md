# Native opened-resource provider proof

This Linux x86_64 checkpoint proves a private provider's bytes and metadata come
from the same opened resource. It does **not** wire that provider into the CLI or
`ManagedFiles`, admit original GHC `__hscore_fstat`, implement RTS file locks, or
establish `putStrLn`/Handle support. Arbitrary acquisition cancellation, weak
references and finalizers remain outside this proof.

## Explicit authority

`NativeIO.createContext` chooses native access and the host filesystem together.
It installs the exact final internal `NativeFileSystem`, constructs the context,
attaches its private provider, and returns a built `Context`, not a configurable
builder. Standard INPUT, OUTPUT and ERROR resources require separate explicit
grants. Each acquired endpoint is an owned native duplicate; its byte operations
and stat image use that duplicate, never metadata for an unrelated `Env` stream.
This is preparation for later integration, not a replacement of current streams.

An ordinary context, even with native access, cannot obtain this provider.
Custom or decorated filesystems are deliberately unsupported: Truffle's public
channel decorator cannot authenticate an arbitrary wrapper's bytes against a
separate metadata resource. The fixed factory excludes acquire-A/return-B
substitution before any acquisition; there is no reflection, channel unwrapping,
path-keyed metadata lookup or process-global descriptor registry.

## Resource lifetime and metadata

Acquisition goes through `Env.getPublicTruffleFile(...).newByteChannel(...)` and
the configured filesystem transaction. A synchronous single-use request retains
rollback ownership until commit. Commit checks that the native lease and its
context provider are still live. Completion followed by failure, duplicate or
late completion, and reentrant provider disposal reject and close the resource.

The private C transport opens regular files without truncation. Nonblocking and
no-controlling-terminal flags prevent FIFO waiting and controlling-terminal
acquisition before the opened type check, not all possible device-open effects.
Other types receive explicit unsupported-policy rejection,
not fabricated native errno. Native failures retain actual captured errno.
Read, write, seek, truncate and full `struct stat` snapshots share one descriptor.
Metadata therefore follows host chmod, size changes, rename, replacement and
unlink without reopening a path. The existing native ABI probe checks the image
layout; returned snapshots do not expose descriptor integers to Core.

The host lease closes once using a Java FFM downcall, so ordinary context disposal
does not re-enter a disposed LLVM context. Rollback and ordinary disposal are
tested, including physical descriptor closure. Cancellation between libc
acquisition and publishing the descriptor into its host lease is **not yet
proved safe**; this checkpoint makes no cancellation guarantee.

## Evidence

`NativeFileProviderTest` has ten fixture-free Kotlin tests for authority denial,
same-resource metadata, nontruncating acquisition, byte-buffer preflights, all
three explicit standard grants, context isolation, rollback, reentrant disposal,
and duplicate/late completion. Linux-only `/proc/self/fd` observations establish
physical closure for unique test paths; procfs is never production metadata
authority. These tests and the existing `ManagedDescriptorDupTest`,
`ManagedFilesTest` and `GuestThreadsTest` passed all 54 cases in both default and
dense handoff modes with normal native-resource compilation enabled. No original
Haskell FCall or compiled guest-entry support is claimed by this provider test.
