# Native files and descriptors

On Linux x86_64 and Darwin x86_64/arm64, THC's command-line IO launcher
uses a native file provider and grants stdin, stdout and stderr. Reads, writes
and metadata come from the same
opened resource, including after a path is renamed, replaced or unlinked.
Guest descriptors belong to the context and never directly name host fds.

Darwin LP64 builds also produce selected-ABI regular-file and descriptor-directory
resources. Admission requires those resources and the generated host metadata,
not a blanket POSIX permission. Darwin uses an opened search-capable directory
and descriptor-relative acquisition; `F_GETPATH` is only an identity-verified name
observation. Relative generic FileSystem callbacks without a descriptor-authority
transport fail explicitly. Original raw open has its own Linux x86_64 and
Darwin x86_64/arm64 resources. Darwin descriptor readiness uses libc
`pselect$DARWIN_EXTSN` with dynamically sized descriptor sets and a private
nonblocking pipe for wakeup; this preserves FIFO EOF readiness. Linux subprocesses, event-manager
poll/eventfd/epoll, terminal images and glibc directory streams remain separate
capabilities;
the Darwin file provider does not admit them.

## Embedding authority

`NativeIO.createContext` constructs a context with the fixed native filesystem
and provider. Standard INPUT, OUTPUT and ERROR resources require separate grants;
each granted endpoint is an owned native duplicate. Ungranted endpoints remain
ordinary embedding streams. Native access alone does not install this provider,
and custom/decorated filesystems are unsupported.

The provider closes its resources on context disposal. Ordinary embedding streams
are flushed but not closed. Grant only the filesystem and standard resources the
application needs; this factory provides host authority, not a sandbox.

## Resource lifetime and metadata

Opened files, [pipes and eventfds](rts-event-capabilities.md#native-anonymous-descriptors)
share the descriptor registry. Logical `dup`/`dup2` aliases share an open
description, offset and IO state; each descriptor closes independently. The last
alias retires the resource. GHC's [RTS locks](original-rts-file-locks.md) remain a
separate table.

The original Posix `fcntl` wrappers forward signed integer commands and optional
CLong arguments to libc. Successful signed results are distinct from errno.
`F_DUPFD` and `F_DUPFD_CLOEXEC` return the lowest available **guest** descriptor at
or above the requested minimum, with independent native leases and descriptor
flags. They share kernel open-file and epoll state. Closing one alias preserves
the remaining aliases and registrations. Ordinary embedding streams do not gain
native flag capability.

Read/write buffers must be checked managed storage or live context-owned native
allocations. Transfers borrow their full requested region; concurrent free waits
for the borrow. Out-of-range, freed and foreign-context pointers fail before IO.
Partial transfers preserve untouched bytes. `fstat` reports the opened resource,
not a newly opened path; failures preserve its destination, and success preserves
errno. Embedding descriptors without native metadata report ENOTSUP.

## Open and cancellation

Original GHC unsafe, safe and interruptible open declarations forward native
flags and the installed mode_t width (Word32 on Linux, Word16 on Darwin). Relative paths use the context directory, retaining
raw bytes, symlink and dot-segment behavior. Descriptor reservation precedes
creation/truncation, and acquisition either publishes ownership or rolls it back.
The private managed-file ABI has a narrower regular-file admission policy;
it is not the same operation as original POSIX open.

Safe and interruptible opens use an owned native worker. Safe calls leave guest
requests queued. An interruptible wait can cancel a blocked open unless masked
uninterruptibly, returning EINTR so the original Haskell wrapper can deliver the
exception. A successful syscall retains its descriptor even if cancellation
races publication. Hard context cancellation joins the worker and releases an
untransferred descriptor.

This worker mechanism requires an unused SIGRTMIN disposition on Linux or SIGUSR1
on Darwin. Setup rejects an
existing owner or later replacement; the embedding host must not concurrently
change it. The handler remains installed until process exit. Cancellation between
an unsafe synchronous syscall and lease publication is not guaranteed safe.
General interruptible byte transfers remain unsupported.

Qualification covers synthetic Word32 Core open on both backends, including
first installed execution, and the owning native request, mask and registry
controls. Actual installed GHC wrappers before/after tidy, native Word16 wrapper
execution, the full three-safety Core matrix and Loom-hosted open remain
unqualified. The Darwin pathname control uses ordinary distinct basenames;
Linux additionally checks distinct invalid UTF-8 basenames through native open.

Forwarding flags does not establish every downstream operation for every flag
combination. O_PATH/access-mode 3, native nonregular terminal classification,
standard-endpoint extension with inherited append state, and some zero-count
nonregular transfer errors retain limitations. See [readiness](managed-fd-waits.md).

## Working directory and path operations

Each native context owns an opened current directory. Guest `chdir` replaces it
transactionally without changing process cwd; relative operations borrow it for
their complete lookup. Rename or replacement of an ancestor cannot redirect an
operation to a stale pathname. Path operations preserve raw bytes.

`getcwd` requires a non-null caller-owned buffer. It writes a trailing NUL on
success and leaves the buffer unchanged on failure. Zero capacity reports EINVAL,
insufficient capacity ERANGE, and a removed current directory ENOENT. GNU
NULL-buffer allocation is unsupported. Linux physical name lookup requires
statx, faccessat2 and procfs. Darwin verifies the name observed by `F_GETPATH`
against the opened directory's native identity. Inaccessible ancestors may
conservatively report EACCES.
Relative IO does not need to recover that physical name first.

Supported original access checks use libc's real-ID permissions, not Java
permission predicates. A successful check does not guarantee that a later open
will succeed. Rename and removal retain native replacement, symlink and errno
behavior. `readlink` returns bytes without adding NUL.

## Directory streams

Original Unix directory calls use context-owned opaque handles on Linux x86_64
with glibc 2.23 or newer. Successful `fdopendir` consumes its guest descriptor
once; failure leaves it with the caller. Other aliases remain valid. Context
close retires remaining streams.

Directory names retain raw bytes and trailing NUL. Entry/name views expire at
the next read or close. Readdir validates its output cells before advancing the
cursor, and native EOF preserves errno when libc leaves it unchanged. Forged,
foreign-context and retired handles are rejected. Arbitrary directory cursor
manipulation and unrestricted host-descriptor import are unsupported.
