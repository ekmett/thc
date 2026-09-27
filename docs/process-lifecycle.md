# Owned process transport

`ManagedProcesses` is an internal Linux x86_64 process service. It launches real
children with libc `posix_spawn`, connects actual pipes or authenticated native
file resources, polls and reaps exit status, sends termination, and owns shutdown
cleanup. It requires both native access and explicit process-creation permission.
File access alone does not grant subprocess authority.

This service is not yet connected to original process-package declaration
admission. `NativeIO` has not acquired a new default process grant. The remaining
adapter must preserve the original declarations and use the existing foreign-call
activation and completion machinery; no capability counts change with this service.

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

Waiting blocks on a private duplicated pidfd through `NativeEventWait`.
Safepoint retries only repeat readiness observation. Reaping takes place after
readiness outside the blocked callback and is never replayed by this service.
The caller supplies its existing interruptible-operation cut. Registry shutdown
wakes waiters, kills only still-owned children, reaps them, and releases leases;
it remains valid after LLVM disposal. Shutdown waits for kernel reaping after
SIGKILL and does not promise a deadline for an uninterruptible kernel task.

## Limits and verification

This transport requires Linux pidfd creation, signalling and wait support and
glibc's `posix_spawn` directory/closefrom actions. It probes pidfd waiting before
creating a child. Credential changes and Windows console flags are rejected
before creation. Process groups and new sessions can be requested at creation;
group signalling, control-C delegation and original Handle/FFI integration are
separate work. A context may not install an independent reaper for these children.

The focused JVM tests compare raw result/status/errno tuples with a Haskell
executable linked against the genuine native process package. The fixture keeps
the original unsafe/interruptible declarations. Other controls exercise pipe
transport, environment and renamed-CWD isolation, denied permissions, foreign
handles, creation failures, cancellation, and context cleanup.

With the pinned GHC and GraalVM selected, run both handoff modes from one build:

```sh
./gradlew --continue testDefault --tests thc.runtime.ManagedProcessesTest \
  testDense --tests thc.runtime.ManagedProcessesTest
```

These are service tests; they do not establish original Core execution or full
`System.Process` support in either interpreter.
