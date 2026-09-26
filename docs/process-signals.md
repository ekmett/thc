<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Standalone process signals

On Linux x86_64, both backends translate original `stg_sig_install`
for SIGHUP, SIGINT, SIGQUIT and SIGTERM. These are the four handlers installed
temporarily by GHC 9.14.1's library-level `runGhc` and `runGhcT`; importing the
compiler library alone does not install them.

DFL, IGN, HAN and RST actions and a null signal mask are supported. Other
signals, non-null masks and Windows delivery remain outside
this bridge's current implementation.

Delivery requires `asyncExceptions=true`. Bytecode enables it by default; AST
retains its synchronous default and needs an explicit opt-in. For a standalone
AST launch, set `THC_BACKEND=ast` and add `-Dthc.asyncExceptions=true` to
`JAVA_OPTS`. `loadEntry(..., asyncExceptions = true)` and the public Core request
expose the same option. A malformed property fails explicitly; omitting it does
not change either backend's default. A synchronous program is rejected before
the native transport is acquired.

## JVM and embedding ownership

The Linux standalone JVM launch scripts include `-Xrs`. This leaves the four
signals above to the application rather than HotSpot's normal signal handling.
The extended bridge checks the effective `ReduceSignalUsage` VM setting and
refuses HUP/QUIT/TERM installation if a later option disables it. The
previous SIGINT-only path remains available for existing direct launches.

Only the explicit NativeIO command-line context can acquire this process-global
service. Ordinary native-enabled embedding contexts cannot replace host process
handlers. The handler slot is never reused by another context in the same JVM;
late delivery from an old context must not target a new context. Code that builds
its own JVM invocation must arrange the same `-Xrs` flag and owning launcher.

With `-Xrs`, SIGQUIT no longer produces HotSpot's ordinary thread dump, and JVM
shutdown hooks are not automatically triggered by INT/HUP/TERM. The Haskell
handler and normal THC context shutdown determine cleanup. Normal `System.exit`
still runs JVM shutdown hooks. This is a standalone launcher policy, not a
recommendation that libraries change the signal policy of an embedding JVM.

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

Verification includes four native GHC action-order oracles, 25 isolated native
process controls, real signal delivery through the FFM bridge in a separate
`-Xrs` JVM, typed dispatcher checks for all four signal numbers, and both runtime
handoff modes. These checks do not claim a complete GHC compiler session works.
See [compiler RTS services](compiler-rts.md) for that separate effort.

The separate `signal-dispatch` full-Core fixture compares original GHC
`setHandler`/`runHandlersPtr` delivery with native GHC for all four signals,
including the actual siginfo CInt, forked handler masking and shutdown cleanup.
It retains byte-identical installed modules and strict pre/post Core audits.
The test transport only queues events; the original Haskell handler registry,
ForeignPtr wrapping and `forkIO` execute on both backends. It does not replace
host process dispositions; the isolated native/JVM controls above cover that
boundary separately.

The full-Core producer needs the production annotated acquisition view when the
bare installation lacks retained Posix foreign-import products. Select that
view with `THC_INSTALLED_CORE_GHC` and `THC_INSTALLED_CORE_GHC_PKG`, as described
in the [complete installed-Core guide](driver.md). The native oracle still uses
`GHC`; missing typed import provenance is an audit failure, not permission to
remove original error-handler branches.

```sh
cabal run exe:thc-fixtures -- signal-dispatch
./gradlew --continue signalDispatchFullCoreDefault signalDispatchFullCoreDense
```
