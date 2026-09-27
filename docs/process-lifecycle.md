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
Every pipe and pidfd is staged into owned native leases before publication.
Returned pipe leases can transfer once to the existing managed descriptor
registry; until transfer, the process service closes them on context disposal.
No unregistered JVM descriptors pass through to children, including when the
original `close_fds` option is false.

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

## Limits and verification

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

The pinned build host has glibc 2.35, without the atomic `pidfd_spawn` interface
introduced in [glibc 2.39](https://sourceware.org/pipermail/glibc-cvs/2024q1/084106.html).
Supporting hosts that cannot maintain the creation invariant requires a separate
atomic creation transport; this implementation does not claim that support.

The focused JVM tests compare raw result/status/errno tuples with a Haskell
executable linked against the genuine native process package. The fixture keeps
the original unsafe/interruptible declarations. Other controls exercise pipe
transport, environment and renamed-CWD isolation, denied permissions, foreign
handles, creation failures, cancellation, and context cleanup. Standalone C
subprocess controls establish real kernel auto-reaping under `SIG_IGN` and both
default/handler `SA_NOCLDWAIT` dispositions, then verify transport rejection before
any libc launch. A forwarding linker wrapper counts real `posix_spawn` calls;
it does not replace their behavior. The default-policy positive control launches
and reaps a real child. These controls never alter the JVM's signal policy.

With the pinned GHC and GraalVM selected, run both handoff modes from one build:

```sh
cabal run exe:thc-fixtures --offline -- process-lifecycle
./gradlew --continue \
  testDefault --tests thc.runtime.ManagedProcessesTest --tests thc.runtime.ManagedProcessForeignTest \
              --tests thc.runtime.CoreProcessForeignTest --tests thc.runtime.ProcessLifecycleCoreTest \
  testDense --tests thc.runtime.ManagedProcessesTest --tests thc.runtime.ManagedProcessForeignTest \
            --tests thc.runtime.CoreProcessForeignTest --tests thc.runtime.ProcessLifecycleCoreTest
```

The Haskell producer rebuilds only the SHA256-pinned, unmodified process 1.6.26.1
source package in a private database, retaining complete Core. Its narrow
`--allow-newer=process:base` relaxes the archive's Cabal bound for the pinned
GHC 9.14.1 base 4.22 without editing any package source. Real FCallIds are read
from those interfaces and specialized into typed consumers; no target metadata
is forged. Pre/post-tidy audits and AST/bytecode tests cover interpreted and first
installed execution, native lifecycle/creation observations, pipe/environment/CWD
transport, actual PID observability, all masking states, interrupted and completed
waits with queued delivery, saved errno and no replay. The service and declaration
tests retain negative provenance, permission, cancellation and resource controls.
