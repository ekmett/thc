<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Standalone process signals

This bridge uses managed threads in either platform or Loom hosting. A Loom
reader releases its HEC during the native wait and reacquires it before dispatch.

On Linux x86_64, both backends translate original `stg_sig_install`
for SIGHUP, SIGINT, SIGQUIT, SIGUSR1, SIGUSR2, SIGTERM, SIGXCPU and SIGXFSZ.
Tasty installs six of these during ordinary startup. HUP/INT/QUIT/TERM are installed
temporarily by GHC 9.14.1's library-level `runGhc` and `runGhcT`; importing the
compiler library alone does not install them.

The same bridge accepts the exact `stg_sig_install` declarations from
`ghc-internal` and the pinned `unix-2.8.8.0-inplace` library. Both use the
original `GHC.Internal.Conc.Signal.runHandlersPtr` dispatcher; admitting Unix's
declaration does not grant an embedding context process-signal authority or
change the supported actions.

DFL, IGN, HAN and RST actions and a null signal mask are supported. Other
signals, non-null masks and Windows delivery remain outside
this bridge's current implementation.

Darwin builds produce a matching machine-code bridge using the selected headers'
signal numbers and `siginfo_t` size. It uses nonblocking close-on-exec pipes for
data and wakeup; Linux retains its existing eventfd wakeup. Resource acquisition
checks the generated ABI against the loaded bridge before installing any handler.
Darwin refuses non-INT dispositions owned by `libjvm.dylib`, and SIGUSR2 remains
unavailable without a verified legal JVM relocation contract. The Linux signal
64 policy below is not transplanted to Darwin.

Delivery requires continuation-capable code, which ordinary AST and bytecode
programs retain even with `asyncExceptions=false`. Binding alone preserves the
single-origin assumption; installing the first handler invalidates it before
native transport acquisition or signal-worker publication. `asyncExceptions=true`
enables ordinary polling eagerly. Ordinary executable launches and raw loads
default to speculative polling on both backends; signal installation does not
require explicit opt-in. A malformed launcher property fails explicitly. Prepared
synchronous code rejects concurrency admission before acquiring the transport.

## JVM and embedding ownership

The Linux and Darwin standalone JVM launch scripts include `-Xrs`. This leaves the four
signals above to the application rather than HotSpot's normal signal handling.
The bridge checks the effective `ReduceSignalUsage` VM setting and
refuses every supported signal except SIGINT if a later option disables it. Direct launches without `-Xrs` can acquire only SIGINT.

Only the explicit NativeIO command-line context can acquire this process-global
service. Ordinary native-enabled embedding contexts cannot replace host process
handlers. The handler slot is never reused by another context in the same JVM;
late delivery from an old context must not target a new context. Code that builds its own JVM invocation must arrange the same startup policy and owning launcher.

The standalone Linux launcher also sets `_JAVA_SR_SIGNUM=64` before starting
Java, reserving signal 64 for HotSpot's suspend/resume mechanism. It preserves
an existing value of `64` and rejects any incompatible supplied value. The
bridge refuses guest signal 64. Guest USR2 installation requires both this
setting and native disposition checks: signal 64 must have a handler in
`libjvm.so`, while USR2 must not. Direct embeddings and interposers that obscure
those dispositions fail explicitly. A guest request never relocates a running
JVM's handler.

On the pinned HotSpot VM, USR1 and XCPU have no JVM handler. HotSpot ignores
XFSZ and permits applications to replace its handler; the bridge saves and
restores it with the other owned dispositions. No `libjsig` preload is required.
The startup policy and checks are Linux x86_64 HotSpot-specific, not a general
embedding or portable JVM contract. HotSpot's suspend handler does not chain;
see [OpenJDK's signal implementation](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/hotspot/os/posix/signals_posix.cpp)
and [Oracle's signal guide](https://docs.oracle.com/en/java/javase/12/troubleshoot/handle-signals-and-exceptions.html).

With `-Xrs`, SIGQUIT no longer produces HotSpot's ordinary thread dump, and JVM
shutdown hooks are not automatically triggered by INT/HUP/TERM. The Haskell
handler and normal THC context shutdown determine cleanup. Normal `System.exit`
still runs JVM shutdown hooks. This is a standalone launcher policy, not a
recommendation that libraries change the signal policy of an embedding JVM.

The experimental application-bound Native Image recipe instead sets
`-R:-EnableSignalHandling`. The bridge checks that the actual runtime option is
explicitly false; an absent/default value does not grant ownership. NativeIO
launcher authority, async delivery, action/mask validation and context lifetime
checks still apply. This also disables Substrate's diagnostic signal handlers,
including its segfault report. The separate HotSpot-specific SIGUSR2 relocation
check is unchanged: this image profile does not admit SIGUSR2.

## Delivery and restoration

The machine-code handler only queues a complete `siginfo_t` through a
nonblocking pipe, using lock-free bookkeeping and preserving errno. It never
calls Java or Haskell, allocates memory or takes a mutex. Queue overflow remains
an explicit failure, not silently lost delivery.

A normal Truffle-owned thread drains the pipe and invokes GHC's original
`runHandlersPtr`, passing the actual signal number as boxed CInt and the owned
siginfo pointer. GHC selects the current Haskell handler. Each signal retains
its own old action; closing restores only handlers installed by this session
that have not subsequently been replaced by the host. Unrelated signals are
untouched. The existing context shutdown/cancellation path wakes and joins the
reader before releasing its native resources.
