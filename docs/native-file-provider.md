# Native opened-resource provider proof

This Linux x86_64 checkpoint proves a private provider's bytes and metadata come
from the same opened resource. The explicit `NativeIO` context now attaches this
capability to shared `ManagedFiles` owners and the original GHC `__hscore_fstat`
adapter. On Linux x86_64, the ordinary
`thc --run-io` launcher uses that fixed context and explicitly grants process
stdin, stdout and stderr. The launcher keeps its compilation settings and permits
guest threads. Other hosts retain the existing managed-file context. Custom
embedding contexts and the scalar CLI path retain their existing IO choices.
The independent original RTS file-lock table does not establish
`putStrLn`/Handle support. Arbitrary acquisition cancellation, weak
references and finalizers remain outside this proof.

## Explicit authority

`NativeIO.createContext` chooses native access and the host filesystem together.
It installs the exact final internal `NativeFileSystem`, constructs the context,
attaches its private provider and descriptor owners, and returns a built
`Context`, not a configurable builder. Standard INPUT, OUTPUT and ERROR resources
require separate explicit grants; the Linux CLI grants all three. Each acquired
endpoint is an owned native
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

Read and write accept both managed byte storage and live, context-owned malloc
allocations. Native aliases retain their exact byte offset. The complete requested
region is validated before IO, then borrowed under the descriptor owner until
the transfer and read copyback finish; concurrent free waits for that borrow.
Channels receive a bounded native byte-buffer view. Embedding streams use at most
1 MiB of staging storage and report the actual short transfer, preserving bytes
outside a successful read. Freed, foreign-context and unowned numeric pointers
remain rejected. This adds no descriptor or foreign-call authority.

Managed native opens register pending acquisition before provider calls, claim
the actual opened identity before truncation, and publish one shared owner.
`dup`/`dup2` aliases share that owner, its IO monitor and metadata capability;
only the last descriptor retires the resource. This is still THC's local
reader/writer admission, not GHC RTS locking. Ordinary custom embeddings retain
their existing path-sampled service and its documented acquisition limitations.
Internal `statImage(fd)` revalidates the descriptor under the same owner; closed
descriptors throw an IO failure and unavailable capability throws unsupported.
The separate errno-returning original `__hscore_fstat` adapter validates the
entire writable destination byte region before observation, including pointer-cell
exclusion. Under the same descriptor owner it locks mutable destination storage,
revalidates the region, obtains the native image, checks its complete length and
copies it back. This preserves owner-before-allocation lock order and prevents
concurrent shrink or pointer writes during observation/copyback. Ungranted
embedding descriptors return native ENOTSUP, closed descriptors return EBADF,
and successful calls preserve the previous errno.

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

### Original unsafe open

The Linux x86_64 `ccall unsafe __hscore_open` declaration is supported only in
the explicit native context. Its Addr#/CInt/Word32/State proof is exact; safe and
interruptible declarations remain rejected. This does not execute the complete
unchanged `openFileWith` acquisition, whose interruptible call is still a gap.

Original open forwards all canonical flags and mode bits unchanged to libc,
including requested truncation/append/creation behavior and the host umask.
No flags are added, no retry occurs, and success does not require a later fstat
or regular-file check. A complete pointer-free NUL-terminated region is copied
before effects; pathname bytes are not decoded as UTF-8. For relative paths,
the fixed host filesystem's public `.` URI supplies the absolute byte anchor;
guest symlink/dot segments are appended without normalization. Empty names stay
empty. The fixed factory does not promise a mutable working-directory API.

A pending acquisition reserves the lowest free context descriptor before
creation or truncation. Both dup and private open skip reservations; dup2 into a
reservation fails with probed EBUSY without changing either owner. Host open runs
outside the registry monitor. Publication or rollback completes before disposal
can finish. Dup aliases retain the same opened lease and independent close life.
Unlike private open, original open acquires **neither** private reader/writer
admission claims **nor** original RTS locks. Subsequent private opens continue
their separate policy; original `lockFile`/`unlockFile` calls govern only the
independent RTS table. Raw close and dup2 never implicitly release RTS entries.

Guest throwTo delivery is deferred within the foreign scope and the completed
descriptor result is saved before the next guest cut. Safe and interruptible
opens use an owned native worker; only waiting for its completion can be retried.
Safe calls leave guest requests queued. Interruptible calls can cancel a blocked
open unless the guest is masked uninterruptibly, returning `EINTR` without
claiming the exception. The original GHC wrapper chooses the subsequent delivery
cut after failure. A successful syscall always retains its fd, even if
cancellation races publication. Hard context cancellation cancels and joins the
worker and closes any untransferred fd before releasing its lease.

This request mechanism is Linux x86_64 only. It claims an unused `SIGRTMIN`
disposition and targets only its native workers, never JVM/Sulong threads. Setup
rejects a pre-existing owner or a later host replacement; it does not overwrite
host handlers. The embedding host must not concurrently mutate that owned
disposition. The handler and machine library remain installed until process exit
and never refer to request memory. Unsafe acquisition retains its existing
synchronous LLVM path; cancellation between its syscall and lease store remains
unproved. Nonregular raw descriptors do not gain a general readiness or terminal
service.

Forwarding raw flags is not a claim that every subsequent descriptor operation
already implements every Linux flag combination. In particular, O_PATH and
access-mode 3 still expose gaps in downstream transfer/truncate/terminal
classification, and zero-count nonregular transfers retain an existing shortcut
that need not match native errors (for example, reading a directory). Existing
readiness, isatty and truncate emulation remains bounded by each operation's
contract. These are follow-on correctness obligations, not flags silently
rejected or rewritten by open.

`OriginalOpenTest` consumes genuine installed pre/post FCallIds and native GHC
observations, checks exact first-installed entry/runRW targets on both backends,
and rejects malformed carriers, stored proofs, unknown safety labels and managed
path memory before effects. Native mode observations use umask022; JVM creation
checks account for its read-only observed process umask without changing it.
Directory type is compared, not filesystem-dependent directory size/permissions.

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
Haskell FCall admission is claimed by this provider test itself.

`OriginalFstatTest` separately checks the exact original pre/post `ccall unsafe`
declaration, both backends and every first-installed compiled invocation against
native GHC. Observations cover size and chmod changes, a duplicate descriptor,
rename/replacement/unlink identity, invalid/closed descriptors, sticky errno and
destination canaries. Malformed State/ABI, short or immutable destinations and
pointer-bearing storage reject before native observation. No full Handle, RTS lock,
arbitrary native pointer, or acquisition-cancellation guarantee follows from this
bounded Linux x86_64 admission.
