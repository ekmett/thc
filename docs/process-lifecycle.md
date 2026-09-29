# Owned process transport

`ManagedProcesses` is an internal Linux x86_64 process service. It launches real
children with libc `posix_spawn`, connects actual pipes or authenticated native
file resources, polls and reaps exit status, sends termination, and owns shutdown
cleanup. It requires both native access and explicit process-creation permission.
File access alone does not grant subprocess authority.

Both interpreters admit the four original process-package declarations below.
`CoreProcessForeign` checks their exact owner, safety and ABI; the shared
`ManagedProcessForeign` adapter marshals context-owned arguments and resources.
`NativeIO.createContext` keeps its default process grant denied. An embedding
can explicitly pass `allowProcesses = true`; the Linux command-line context
opts in. The existing one-argument JVM factory remains available.

## Original declarations

The GHC 9.14.1 source package `process-1.6.26.1` declares:

| Symbol | Safety | Native arguments | Result |
| --- | --- | --- | --- |
| `runInteractiveProcess` | unsafe | argv, cwd, environment, three descriptors, three output descriptor pointers, group pointer, user pointer, flags, failure-location pointer | signed 32-bit PID |
| `getProcessExitCode` | unsafe | PID, output CInt pointer | CInt |
| `waitForProcess` | interruptible | PID, output CInt pointer | CInt |
| `terminateProcess` | unsafe | PID | CInt |

These declarations are in `System/Process/Posix.hs` and `System/Process.hs`;
their POSIX implementation is in `cbits/posix/runProcess.c`. Native exit statuses
are zero for success, the positive exit code for ordinary failure, and negative
signal numbers for signal termination.

Raw polling returns zero while running and one after reaping; it initializes the
output code to zero even on failure. Its original ECHILD branch returns one with
code zero and leaves ECHILD in errno. Raw waiting returns zero after reaping and
minus one on error, leaving the output untouched on error. Thus a raw wait after
another completed wait fails with ECHILD. `ProcessHandle` caching is implemented
by the Haskell package above these calls; the transport does not invent a second
native exit-code cache.

The POSIX `terminateProcess` C wrapper returns **one on successful kill and zero
on failure**, with native errno on failure. This differs from the minus-one test
in its Haskell caller. The transport preserves the original C result.

## Ownership and cancellation

Only identities issued by a registry identify its children. Native PIDs are
retained for an authenticated `getPid` adapter; they never grant signalling or
reaping authority. Those operations use owned Linux pidfds with
`pidfd_send_signal` and `waitid(P_PIDFD)`, so a reaped child's PID cannot redirect
an old handle to a new process. Reaping retires the pidfd immediately and leaves
a small authenticated tombstone with the original raw ECHILD/ESRCH behavior;
repeated launches do not accumulate native descriptors until context shutdown.

The directory input is a borrow of `NativeDirectoryOwner`, not the process CWD.
Spawn applies `fchdir` to that anchor and then the optional raw-byte relative cwd
in the child. Environment entries are an explicit complete child snapshot. The
separate search-path input is the parent context's PATH: as with original libc
`posix_spawnp`, a child environment override does not change executable lookup.
Relative and empty PATH components work without changing the JVM environment.
A missing parent PATH uses libc's `_CS_PATH` default.

Inherited descriptors come from authenticated `NativeFileResource` duplicates.
With `close_fds = False`, open guest descriptors numbered 3 or above whose
`FD_CLOEXEC` flag is clear retain those same descriptor numbers in the child.
Their numbers in argv need no rewriting. Setting `FD_CLOEXEC` on a guest
descriptor, or setting `close_fds = True`, excludes it from this inheritance.
Explicit stdin, stdout and stderr selections still follow their requested
mapping. No unregistered JVM descriptors pass through to children.

Native staging preserves the mapping when a source descriptor is also another
mapping's destination. Every temporary descriptor, pipe and pidfd remains owned
until publication or rollback. Returned pipe leases can transfer once to the
existing managed descriptor registry; until transfer, the process service closes
them on context disposal.

`ManagedFiles.launchProcess` reserves guest descriptors before launch, pins
authenticated inherited resources, and adopts returned pipe leases through its
native provider. Failed publication aborts only that launch and releases adopted
resources. Numeric original CPids retain the actual native PID: published numbers
are never reassigned within the context. A reused PID colliding with a retained
identity rejects and cleans the new child through its own pidfd, not the old
numeric PID. This conservative policy preserves stale-handle isolation at the
cost of rejecting a launch if the host eventually reuses a retained PID.

The native creation result carries a checked failure-stage code. Ordinary
flags-zero missing-command and missing-CWD failures retain the original
`posix_spawnp` label and errno. With `close_fds`, the original package falls back
to fork/exec and can instead report `chdir`, `execvp`, or `execvpe`. This transport
still reports its actual spawn-stage label; it does not fabricate fork diagnostics.
The ABI adapter allocates context-lifetime failure strings, writes null on success,
preserves non-pipe output cells, and validates all output cells before launch.

Waiting blocks on a private duplicated pidfd through `NativeEventWait`.
Safepoint retries only repeat readiness observation. Reaping takes place after
readiness outside the blocked callback and is never replayed by this service.
The original interruptible wait observes pending asynchronous work while inside
its foreign activation, without claiming delivery there. Before a genuinely
blocking native poll it can return EINTR, leaving the output and child untouched.
MaskedUninterruptible defers this cancellation. An already completed reap wins;
the interpreter saves both its result and errno before the completion poll.
Resumption restores that errno even on another carrier and never repeats the
wait or reap. Registry shutdown
wakes waiters, kills only still-owned children, reaps them, and releases leases;
it remains valid after LLVM disposal. Shutdown waits for kernel reaping after
SIGKILL and does not promise a deadline for an uninterruptible kernel task.

## Platform and host requirements

This transport requires Linux pidfd creation, signalling and wait support and
glibc's `posix_spawn` directory/closefrom actions. It probes pidfd waiting before
creating a child. SIGCHLD set to `SIG_IGN`, or with `SA_NOCLDWAIT`, is rejected
with `ENOTSUP` before pipes or a child are created. Credential changes and Windows console flags are rejected
before creation. Process groups and new sessions can be requested at creation;
group signalling, control-C delegation and complete original Handle integration
are separate work. These four declarations do not imply complete System.Process.

The embedding host must keep SIGCHLD policy stable during creation and must not
independently reap these children, including in signal handlers or other threads.
The interval between `posix_spawn` and `pidfd_open`, including raw-PID cleanup if
pidfd acquisition fails, relies on the unreaped child reserving its PID. These are
the documented [Linux pidfd acquisition conditions](https://man7.org/linux/man-pages/man2/pidfd_open.2.html).
The policy check observes current state; it does not lock out concurrent host
changes or establish isolation from a hostile host. Once acquired, the owned pidfd
prevents later PID reuse from redirecting signalling.
