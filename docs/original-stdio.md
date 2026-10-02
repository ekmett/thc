# GHC file and errno boundary

Original GHC and Unix library calls use THC's context-owned descriptor table for
file effects. A guest descriptor is not a host process fd: descriptor 1 names
the context's output, which may be an embedding stream or an explicitly granted
native endpoint. The Haskell library still implements Handle buffering and
higher-level error handling.

Reads and writes preserve offsets, binary bytes, partial counts and errno.
Invalid storage, out-of-range requests and malformed CInt carriers fail before
effects. A nonempty write making no progress reports EIO, preventing the original
Haskell retry loop from spinning indefinitely. Safe calls save their result
before a post-call async poll; they do not replay a completed native transfer.
General interruptible read/write transport remains unsupported.

## Descriptors and metadata

The [native file provider](native-file-provider.md) supplies actual opened-resource
metadata, duplication, fcntl, directories and selected pathname operations on
Linux x86_64. It keeps resource identity through rename and unlink. Ordinary
embedding streams lack native stat, descriptor flags and readiness capabilities;
those requests return ENOTSUP rather than inferring metadata from a path.

Seek constants come from the build host's C ABI. Guest operations do not assume
SEEK_SET/CUR/END are 0/1/2. Supported stat and terminal-image accessors validate
complete caller-owned byte regions using the selected platform layout. See
[terminal images](original-termios.md).

Logical [dup/dup2 aliases](original-posix-dup.md) share an open description.
GHC's [RTS lock table](original-rts-file-locks.md) is separate bookkeeping;
closing a descriptor does not implicitly release an RTS key.

## Errno and readiness

The errno slot belongs to the current context and Java carrier. The original
getter/setter share it; the setter accepts any canonical signed CInt, including
zero. Native failures publish their captured errno. Successful managed file
operations preserve the previous value. Generic package calls instead preserve
the actual native call's resulting errno, including zero.

`fdReady` uses direction-sensitive native polling for supported resources.
Regular files are ready even at EOF. Closed or unknown nonnegative descriptors
also report ready, matching GHC's treatment of POLLNVAL; the following IO call
reports the descriptor error. Negative descriptors support zero-timeout probes
only. Opaque embedding streams have no readiness contract and return ENOTSUP.
Readiness does not consume bytes or change file position.

[Descriptor waits](managed-fd-waits.md) retain resource identity across close,
dup2 and context cancellation. The [waitRead#/waitWrite# primitives](file-wait.md)
have a separate resumable exception contract.

## Other native operations

Ordinary host identity, configuration, time and resource queries use their
[declared native linkage](interface-foreign.md). Selected Unix pathname and
descriptor operations adapt context-owned resources before calling libc. These
retain raw pathname bytes and do not change the JVM process working directory.

Process replacement/fork, credentials and global process changes, passwd/group
pointer graphs, named semaphores/shared memory and arbitrary dynamic loading
remain unsupported. Use the separate [owned process service](process-lifecycle.md)
for its admitted creation/wait/termination operations.
