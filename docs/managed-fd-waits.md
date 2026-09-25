<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Managed descriptor readiness — source checkpoint

This checkpoint adds the native waiting substrate, **not yet admission of
`waitRead#` or `waitWrite#`**. It has not yet been compiled or run. The checks below
are regression test source, not claimed passing evidence.

## Selected GHC path

GHC 9.14.1 `GHC.Internal.IO.FD.readRawBufferPtr` and `writeRawBufferPtr` use the
actual `fdReady` declaration before potentially blocking transfers; the retry
paths wait after EAGAIN. Its exact C signature is signed CInt, CBool, signed
Int64 milliseconds, CBool, State, returning State/CInt. The safe and unsafe
declarations keep their existing separate certificates. No foreign body or
declaration is fabricated.

`GHC.Internal.Conc.IO.threadWaitRead`/`threadWaitWrite` select the Event manager
when `threaded` is true, and the raw `waitRead#`/`waitWrite#` primops otherwise.
Those primops have `Int# -> State# s -> State# s`, not an errno result. The RTS
select manager raises the genuine `GHC.Internal.Event.Thread.blockedOnBadFD`
`SomeException` CAF when a waited descriptor closes. That implicit runtime
dependency must be acquired from complete installed Core and retained by
reachability before either primop can be admitted. The internal wait token's
`ClosedChannelException` is deliberately **not** a Haskell substitute.

The pending arithmetic-exception implementation provides a pattern for runtime
CAF dependencies. This work does not copy library unfoldings, manufacture a
payload, admit the threaded Event manager, or assert complete Handle support.

## Native transport and lifetime

Only the explicit Linux x86_64 NativeIO provider creates readiness capabilities.
They use the same authenticated opened-resource lease as byte transfers and
metadata, never a pathname or a numeric guest descriptor treated as a host fd.
An outstanding wait owns a close-on-exec duplicate of that lease plus a private
nonblocking eventfd. `poll` observes the resource and wake descriptor together;
there is no periodic busy-spin, timer worker or installed signal handler.

Truffle's blocked-thread API registers a custom interrupter. Its nonblocking
eventfd write wakes the native poll for safepoints and context cancellation.
Guest async delivery is considered only at the internal token's explicitly
resumable cut, when actually blocking. Masked-interruptible waits permit it;
uninterruptible masking defers it. Existing original safe/unsafe fdReady calls
remain foreign extents and do not gain guest async interruption. A host
context cancellation can nevertheless wake their blocking native poll.

Each token retains the exact logical descriptor object. Close, dup2 replacement
and context disposal invalidate that object and wake its waiters before physical
owner retirement. Aliases have independent logical wait sets. A resumed token
cannot resolve its old number to a new descriptor. Native duplicates prevent
host fd reuse during poll; they are unregistered and closed on all exits, including
guest async unwind and host cancellation. A token retains no native handle
between attempts. Wake and cleanup are host FFM calls, not re-entry into LLVM.

Readiness duplication shares a short native lifetime lock with physical close,
not the monitor held across a potentially blocking byte transfer. No registry
or descriptor IO monitor is held during poll. Logical close may precede completion
of an already-started byte transfer; physical owner retirement still waits for
that existing transfer. Interruptible byte transfer and native open-acquisition
cancellation are separate, unchanged obligations.

Regular managed files retain justified immediate readiness, including EOF.
Native pipes and other native descriptors use actual direction-sensitive poll
events; error/hangup events count as ready just as the selected fdReady C body
does. Its unknown/closed nonnegative descriptor result remains one (POLLNVAL),
while negative descriptors remain limited to zero-timeout probes. Opaque Env
streams still return ENOTSUP: available bytes are not a readiness contract.
Successful observations preserve sticky errno and never consume stream bytes.

Linux constants and pollfd layout are confined to a lazily linked private native
helper; macOS builds do not link eventfd or include Linux-only headers. This is
not a portable native readiness implementation.

## Regression source and next proof

`NativeFdWaitTest` uses actual nonblocking FIFOs for data absence, finite timeout,
read readiness, EOF/hangup, full-pipe write backpressure, all three masking
states, logical close/dup2/reuse, native duplicate cleanup, host context
cancellation and opaque-stream denial. Its small registry polling loop is a
bounded test barrier only, never the production waiting implementation. These
are service/protocol tests, not a synthetic replacement for native GHC evidence.

Before admission: run focused JVM/native-provider controls under the shared gate;
add the exact installed blockedOnBadFD dependency using the production complete-Core
provider; prove both raw primop contracts and saved descriptor-token retry in AST
and bytecode; compare genuine pre/post Core plus native GHC FIFO observations,
including catch of the original bad-fd exception and async interruption with
effect-prefix-once resumption. Update capability inventory only after that proof.

Reference for the native interruption integration:
[TruffleSafepoint](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/TruffleSafepoint.html)
and its [Interrupter contract](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/TruffleSafepoint.Interrupter.html).
