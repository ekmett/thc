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
`putStrLn`/Handle support. Original safe and interruptible open use the bounded
owned-worker cancellation path below; cancellation during synchronous unsafe
acquisition remains unproved. Weak references and finalizers are outside this
provider proof.

## Explicit authority

The original GHC `__hscore_o_*` getters for append, create, no-controlling-terminal,
nonblocking and the three access modes, plus `__hscore_f_getfl`/`__hscore_f_setfl`,
use the generated host C ABI constants. The exact original Posix CAPI `fcntl`
wrappers support `F_GETFL`, `F_SETFL` and `F_SETFD` through a context-owned native lease.
The setter retains its `CLong` argument; the kernel determines which status bits
can change. Guest `dup` aliases observe the same open-description flags, and
success preserves the guest's sticky errno. `F_SETFD` changes the owned native
resource's descriptor flags; logical aliases do not provide a separate fork/exec
inheritance model. Other commands are explicitly
unsupported; a guest integer never names an arbitrary host descriptor. Ordinary
embedding streams have no native flag capability. These calls retain the
original unsafe FFI contract and do not add interruptible byte transfers.

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

Original [pipe/eventfd acquisition](rts-event-capabilities.md#native-anonymous-descriptors)
creates anonymous kernel resources under the same explicit provider authority.
Their guest descriptor numbers, byte transfers, status flags, duplication and
close use the same context registry as opened files.

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
outside a successful read. A stream that writes a prefix before throwing retains
those writes, as with a managed destination. Freed, foreign-context and unowned
numeric pointers remain rejected. This adds no descriptor or foreign-call authority.

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
tested, including physical descriptor closure. For this synchronous private
acquisition path, cancellation between libc acquisition and publishing the
descriptor into its host lease is **not yet proved safe**. The original safe and
interruptible open declarations use the separate owned request below.

## Evidence

### Original unsafe, safe and interruptible open

The Linux x86_64 original `__hscore_open` declarations are supported in their
exact unsafe, safe and interruptible forms only in the explicit native context.
Their Addr#/CInt/Word32/State proof is exact. The unchanged `openFileWith` path
can reach its original interruptible declaration; this open contract alone does
not establish every subsequent Handle operation.

Original open forwards all canonical flags and mode bits unchanged to libc,
including requested truncation/append/creation behavior and the host umask.
No flags are added, no retry occurs, and success does not require a later fstat
or regular-file check. A complete pointer-free NUL-terminated region is copied
before effects; pathname bytes are not decoded as UTF-8. Relative paths use the
context's borrowed directory descriptor with `openat`; guest symlink/dot segments
are not normalized. Empty names stay empty. Safe and interruptible workers retain
that same borrow until completion or cancellation has joined the worker.

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

### Original access checks

Linux x86_64 supports the original `ghc-internal` unsafe `access` declaration
with exact `Addr#/Int32#/State# -> (# State#, Int32# #)` metadata. The explicit
native filesystem applies libc's real-user/group permission rules, including
mode combinations and invalid-mode errors. It does not use Java permission
predicates or treat a successful check as a guarantee that a later open succeeds.

The pathname is a checked, borrowed NUL-terminated byte snapshot, anchored to
the context filesystem's current directory without decoding or normalizing
guest path bytes. State and the canonical signed CInt mode are checked before
observation. Errors update guest errno immediately; success retains its previous
value. Context ownership and freed-storage checks apply to native path aliases.
The native GHC oracle supplies expected results for files, directories, symlinks,
raw-byte names, denied traversal, invalid modes and missing paths without
assuming whether the process has root privileges.

### Context working directory

The fixed Linux x86_64 NativeIO factory shares one opened directory between its
filesystem and native provider. Original Unix 2.8.8.0 unsafe `chdir` acquires an
`O_PATH` directory, checks effective-ID search permission with `faccessat2`, and
publishes the replacement once. Failure leaves the current directory unchanged.
The Env setter uses the same transaction; public and internal Truffle files see
the same owner. Initialization is idempotent. The JVM/process working directory
never changes, and separate contexts retain independent owners.

Each relative operation borrows an owned duplicate for its complete lookup.
Native open/stat/access/mode/link/unlink families use their descriptor-relative
POSIX calls, preserving raw bytes and symlink/`..` semantics. Ordinary filesystem
operations resolve through the private borrowed `/proc/self/fd` handle; directory
streams retain it through close and expose paths under the requested directory.
Renaming or replacing an ancestor cannot redirect an operation to the old name.
Disposal rejects new borrows and closes the current owner; in-flight borrows close
when their operation finishes. Linux consumes a directory descriptor on close
even on EINTR, so private anchor cleanup never retries or changes a completed
operation's reported result. Ordinary opened-file close errors remain reported.

Original unsafe `getcwd` supports a non-null, caller-owned byte buffer. It checks
the entire writable range before observing the directory, stages the name,
writes one trailing NUL on success, and returns the identical address and offset.
Failure leaves the buffer unchanged. Zero capacity reports EINVAL; insufficient
capacity reports ERANGE. Success preserves errno. GNU NULL-buffer allocation is
explicitly unsupported; no unowned address is returned.

Physical naming verifies procfs text against the directory's mount/device/inode
identity and falls back to a descriptor walk for long names. It does not strip a
literal ` (deleted)` suffix. Rename produces the new physical name, while a
removed directory reports ENOENT and keeps its live handle for remaining relative
operations. This requires Linux `statx` mount identity, `faccessat2`, and procfs
for ordinary filesystem resolution. The verification/walk can conservatively
report EACCES for inaccessible ancestors where the kernel's process `getcwd`
would succeed. Env pathname queries have the same limitation; relative IO does
not first recover a physical name.

`OriginalCurrentDirectoryTest` compares genuine source-built Unix FCallIds with
native GHC in an isolated child process, using guarded output buffers, raw names,
rename/deletion, long names and exact first-installed AST/bytecode calls.
`NativeDirectoryFileSystemTest` checks context isolation, transactional setters,
ordinary IO, stream path/lifetime behavior and descriptor reuse. The native
oracle restores only its own child CWD; the coordinator and JVM stay unchanged.

## Original directory streams

The original Unix 2.8.8.0 unsafe `opendir`, `fdopendir`, `closedir`,
`__hscore_readdir`, `__hscore_d_name`, and `__hscore_free_dirent` declarations
use context-owned opaque handles on Linux x86_64 with glibc 2.23 or newer. The
CAPI wrapper owner, module, index, safety, and exact argument/result types stay
authoritative. Opening a pathname borrows the current directory descriptor and
retains native directory identity through rename or unlink.

Successful `fdopendir` consumes its guest descriptor exactly once; failure
leaves it owned by the caller. Other duplicate guest descriptors stay valid and
share the original kernel open-file description. No guest integer is treated as
a raw host descriptor. Context disposal closes remaining streams.

Readdir validates the writable pointer cell, alignment, ownership, and possible
alias with the current name before advancing the native cursor. It publishes an
entry or NULL and preserves the guest errno seed when native EOF leaves errno
unchanged. Names retain their raw bytes and trailing NUL. Entry/name views expire
at the next read or close; the selected glibc Unix helper's `free_dirent` is a
no-op and does not prematurely retire a view. Cross-context, forged, and retired
handles fail explicitly. Guest output cells may be managed or live native
allocations; stream and entry identities cannot be fabricated from pointer bits.

`OriginalDirectoryStreamsTest` compares six genuine source-built Unix FCallIds
with native GHC across pathname, descriptor alias/failure, raw-name, EOF, rename,
and deleted-directory observations. Both backends preserve exact first-installed
entry counts and compiled target validity. `NativeDirectoryStreamsTest` covers
handle lifetime, disposal, and pre-effect output validation.
