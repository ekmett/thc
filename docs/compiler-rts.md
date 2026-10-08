<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# Compiler-library RTS services

THC translates the following GHC 9.14.1 compiler-runtime interfaces. This is not
support for the native GHC object loader or a claim that every GHC API works.

* `getOrSetLibHSghcFastStringTable` uses the same first-writer-wins StablePtr
  mechanism as the event-manager and signal-handler shared CAFs. The FastString
  slot is separate, belongs to one THC context, retains its winning pointer until
  context disposal, and never evaluates the table. Losing pointers remain owned
  by their callers. Foreign, stale and prematurely freed winners reject.
* `getOrSetLibHSghcGlobalHasPprDebug`, `getOrSetLibHSghcGlobalHasNoDebugOutput`
  and `getOrSetLibHSghcGlobalHasNoStateHack` have separate slots in the same
  context-owned table. Original `GHC.Utils.GlobalVars` creates and updates its
  own `IORef Bool` values; THC retains the opaque winning StablePtrs without
  evaluating, replacing, or merging those flags. Concurrent callers see one
  winner per slot, and separate contexts never share compiler-global state.
* `keepCAFsForGHCi` returns true: live THC programs already retain their global CAF
  cells, and THC does not perform native RTS CAF reversion. This does not load
  GHC's constructor function, mutate a native RTS, promise permanent retention
  beyond the program/context lifetime, or implement native object unloading.
* `setHeapSize` is an ignored JVM heap-size advisory. The original compiler call
  evaluates its `Int#` byte count and state token and returns the singleton state
  tuple. Native GHC uses it to suggest a heap size, potentially raising its native
  maximum; THC cannot resize a context's share of the process-wide JVM heap. It
  does not change JVM-global heap settings, request collection, record unused
  state, or fabricate an RTS flag image. The adjacent `enableTimingStats` call
  remains unsupported.
* `ghc_unique_counter64` and `ghc_unique_inc` are mutable eight-byte data cells,
  initially zero and one, shared by compiler sessions in one THC context. The
  original GHC code reads/writes the increment and uses the ordinary atomic
  Addr# primops on the counter. THC's existing address atomics synchronize on the
  shared backing storage, including aliases. Values wrap at machine width;
  range/alignment checks, context ownership and disposal remain enforced. These
  cells do not refer to the host GHC RTS or manufacture process addresses.

The function declarations require the exact original compiler unit
`ghc-9.14.1-inplace`, unsafe `ccall`, and the original saturated primitive ABI.
Data labels require an evaluated scalar `AddrRep` proof. Unknown labels reject.
Installed native companion acquisition excludes this compiler unit's registered
archive: its three C products implement native RTS/process state, including the
`keepCAFsForGHCi` constructor. Original foreign calls and their ABI remain intact;
this does not provide implementations for unsupported compiler RTS services.

`runGhc` installs handlers for SIGQUIT, SIGINT, SIGHUP and SIGTERM. The
[standalone process signal bridge](process-signals.md) admits these on Linux
x86_64 launches with `-Xrs` and asynchronous continuations enabled. The executable launcher enables this on both backends; ordinary embeddings
remain unauthorized.
Native object loading/GHCi, compiler RTS flags and other reachable foreign calls
must be tested and implemented as they are encountered. These translations do
not bypass strict Core admission.

`CompilerRtsTest` checks these THC-owned compiler CAFs, `keepCAFsForGHCi` and
unique cells without fixture acquisition. Typed synthetic Core exercises the live
function declarations and data labels on both backends, including the first
compiled call; direct ABI controls reject wrong owners, safety, arity and
function/data target kinds. Existing controls cover context isolation, disposal, retained
winners, aliasing, wraparound, alignment and concurrency. Expected results follow
independently from install/query identity, true CAF retention, and counter delta
three plus increment one. These tests do not qualify loading the original compiler
package or recovering its foreign declarations from retained installed interfaces.
