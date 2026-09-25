# Native opened-resource provider proof

This Linux x86_64 checkpoint proves a private provider's bytes and metadata come
from the same opened resource. The explicit `NativeIO` context now attaches this
capability to shared `ManagedFiles` owners. It does **not** change the default CLI,
admit original GHC `__hscore_fstat`, implement RTS file locks, or
establish `putStrLn`/Handle support. Arbitrary acquisition cancellation, weak
references and finalizers remain outside this proof.

## Explicit authority

`NativeIO.createContext` chooses native access and the host filesystem together.
It installs the exact final internal `NativeFileSystem`, constructs the context,
attaches its private provider and descriptor owners, and returns a built
`Context`, not a configurable builder. Standard INPUT, OUTPUT and ERROR resources
require separate explicit grants. Each acquired endpoint is an owned native
duplicate; its byte operations and stat image use that duplicate, never metadata
for an unrelated `Env` stream.
All granted endpoints are acquired before their descriptors are installed;
failure disposes the unpublished context. Ungranted endpoints retain ordinary
embedding streams. Explicit standard grants may coexist even when their inodes
match; inode equality never merges independent open-file descriptions.

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

Managed native opens register pending acquisition before provider calls, claim
the actual opened identity before truncation, and publish one shared owner.
`dup`/`dup2` aliases share that owner, its IO monitor and metadata capability;
only the last descriptor retires the resource. This is still THC's local
reader/writer admission, not GHC RTS locking. Ordinary custom embeddings retain
their existing path-sampled service and its documented acquisition limitations.
Internal `statImage(fd)` revalidates the descriptor under the same owner; closed
descriptors throw an IO failure and unavailable capability throws unsupported.
It is not the future errno-returning original fstat adapter.

Native errors retain exact captured errno for original stdio calls, alongside
the private service's portable error categories. Success preserves prior errno.
Native nonregular standard endpoints are not classified as regular merely
because they have channels: readiness and terminal queries remain explicitly
unsupported without their actual host queries. Standard endpoint extension is
also rejected because inherited O_APPEND status is not yet provided. Regular
native files are known nonterminal; arbitrary Env stream behavior is unchanged.

The host lease closes once using a Java FFM downcall, so ordinary context disposal
does not re-enter a disposed LLVM context. Rollback and ordinary disposal are
tested, including physical descriptor closure. Cancellation between libc
acquisition and publishing the descriptor into its host lease is **not yet
proved safe**; this checkpoint makes no cancellation guarantee.

## Evidence

`NativeFileProviderTest` includes fixture-free Kotlin tests for authority denial,
same-resource metadata, nontruncating acquisition, byte-buffer preflights, all
three explicit standard grants, context isolation, rollback, reentrant disposal,
duplicate/late completion, shared native descriptor ownership, claim-before-
truncate, transactional standard installation and exact error propagation.
Linux-only `/proc/self/fd` observations establish
physical closure for unique test paths; procfs is never production metadata
authority. The ownership checks run alongside `ManagedDescriptorDupTest`,
`ManagedFilesTest`, `GuestThreadsTest` and existing stdio/ABI regressions in both
handoff modes with normal native-resource compilation enabled: 75 tests passed
per mode, including unchanged first-installed compiled-call checks. No new original
Haskell FCall admission is claimed by this provider test.
