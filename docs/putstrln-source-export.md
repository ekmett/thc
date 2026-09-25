<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->
# Ordinary `putStrLn`: source export and dependency inventory

This is an export/audit recipe, **not runnable THC console IO**. It compiles real
GHC library algorithms; it does not substitute a special `putStrLn`, invent
externals, weaken the capability table, or disable Core lint. A successful script
exit means its bounded exports succeeded, not that the resulting audit passed.

The source reference is official GHC 9.14.1, tag `ghc-9.14.1-release`, commit
[`902339d332fb4ce2b3c87dcac1ee6495d41ad886`](https://github.com/ghc/ghc/tree/902339d332fb4ce2b3c87dcac1ee6495d41ad886).
The recipe checks that commit and a clean Git checkout. A sparse checkout must
include `libraries/ghc-internal`, `rts`, and `utils/fs`; no upstream source or
generated upstream Haskell is vendored here. Use a matching native GHC 9.14.1
installation with `ghc-pkg`, `hsc2hs`, dynamic interfaces, and installed headers.
Installed toolchain artifacts are neither modified nor hashed.

## Reproduce a bounded export

Set `GHC_SOURCE` to the official source checkout. Put the pinned toolchain on
`PATH`, or set `GHC` to its executable. The recipe selects sibling `ghc-pkg` and
`hsc2hs` tools; explicit `GHC_PKG`/`HSC2HS` or CLI overrides must resolve to the
same installation's bindir. From the THC repository root:

```sh
python3 scripts/putstrln_hsc.py --ghc-source "$GHC_SOURCE"
python3 scripts/putstrln_export.py --ghc-source "$GHC_SOURCE" \
  --generated-manifest build/putstrln-generated/provenance.json \
  --rounds 8 --max-modules 30
python3 scripts/putstrln_inventory.py build/putstrln-source-only
python3 scripts/test-putstrln-export.py
```

Every output directory must be fresh; `--out` selects a different directory for
another run. Failed commands, partial exports and generated native probes stay
in that output directory for inspection. They are not committed. `--module` can
limit hsc2hs generation; omit `--generated-manifest` for an initial `.hs`-only
frontier. `--rounds 2 --max-modules 1` is a small Main-plus-library smoke check.
The research closure used `--rounds 20 --max-modules 140`; that is an optional
larger experiment, not a default test or a guarantee for another plugin revision.
Module limits exclude Main. The last round always audits the exact admitted set.

The packaged recipe was checked on base `f72f924` with policy/provenance unit
tests, all seven actual hsc2hs generations, and a three-library-module export
(Main, CString, System.IO, Handle.Text). That bounded export had 403 supplied / 48
reachable bindings, 29 missing globals and 64 strict audit issues. All compiler
attempts admitted to it passed lint, dependency interfaces were restored, and no
ordinary imported interface bodies were admitted. The full 112-module research
closure was not rebuilt for this packaging change.

Every export builds its own plugin under `out/plugin` using snapshots of this
checkout's `compiler/THC/*.hs`, forced fresh objects and the configured GHC.
There is no external `--plugin` option and no package registration. The recipe
records the exact source and binary hashes, command and exit, and checks the
captured sources/binary before and after use and again during inventory. A stale
binary or capability flag from another checkout is not accepted. The recorded
Git revision identifies the checkout; captured hashes identify the actual inputs,
including any local changes. Auditor/helper scripts, capability/signature data,
and recipe scripts are also hashed and verified for a coherent audit snapshot.
The state records tool paths, GHC configuration, source revision/hashes, requested
and matched roots, commands, exits, exported JSON hashes, and configuration-only
copies. `latest-audit.json` keeps the strict existing auditor's actual verdict;
`merged-core.json` is evidence, not an executable distribution bundle.

## Why these compilation rules matter

Every source module, including Main, uses Haskell2010, `-O2`,
`-fignore-interface-pragmas`, post-Tidy export, and `-dcore-lint`. This is a
declared alternative optimization recipe, **not identical installed optimized
Core**. The language is the package's actual default; GHC2024 caused real kind
errors in early experiments.

Installed `.dyn_hi` files are exposed through private `.hi` symlinks for GHC's
same-unit imports. Before compiling a module its own symlink is removed; after
the attempt, its emitted `.hi` is moved to isolated module outputs and the
installed symlink is restored, including after failure. Dependency compilations
never consume another freshly exported module's ordinary interface. Required
`.hs-boot` files are compiled from original source, not replaced with fabricated
types. Only successful, validated records enter the merge; directory globs must
not admit JSON emitted before a later compiler/plugin failure.

An installed caller can refer to optimizer-private `ioException3 :: [Char]`
while a new independent source compilation assigns that same name to `Addr#`.
Mixing those interfaces produced a genuine Core-lint failure. Equal generated
names are not a stable ABI. An installed exception-show unfolding also referred
to `FileHandle` while a SOURCE import selected an abstract Handle boot view.
Suppressing ordinary imported unfoldings avoids both observed hazards; replacing
types or disabling lint does not solve them. `-fomit-interface-pragmas` controls
output and is not a substitute for `-fignore-interface-pragmas` on input.

GHC retains compulsory unfoldings. The recipe admits only three inspected
constructor wrappers from the research closure: `$WHRefl`, `$WRetFun`, and
`$WCatchRetryFrame`, each with its full module identity. Unexpected interface
bodies, missing requested roots, source-binding collisions and differing
constructor records fail validation. This narrow allowlist is not a general
proof of interface coherence; review new wrappers against original definitions.

Source `OPTIONS_GHC -O2` can reset ignore-interface-pragmas after command-line
flags. For the two known original modules, `Encoding.UTF8` and
`Float.ConversionUtils`, the script writes a configuration-only copy appending
that flag after the original optimization option. It preserves the algorithm,
line count and original checkout. Other optimization overrides fail closed.
Neither of these two modules was reached in the final research closure.

## Generated sources and configuration

`putstrln_hsc.py` invokes real installed hsc2hs for ExecutionStack.Internal,
InfoProv.Types, Heap.Constants, Heap.InfoTable.Types, Heap.InfoTable, Stack.CCS,
and Stack.Constants. It uses GHC's configured C compiler and installed
ghc-internal/RTS headers; verbose command logs and native sizeof/offsetof probe
inputs are retained. Cross compilation is explicitly unsupported. The manifest
records the GHC configuration, generator source hashes, input/output hashes and
each exit status; export checks that manifest against the selected source and GHC
configuration. Package queries explicitly use GHC's `--print-global-package-db`,
not ghc-pkg's default database. Compilations clear the package stack and use that
database without a user package environment. Include/import directories must be
inside the selected GHC libdir. The sibling hsc2hs receives that libdir's explicit
`template-hsc.h`; a same-version executable or manifest from another installation
does not establish matching layouts. Earlier manifests without this binding are
rejected. No installed artifacts are hashed.

In the research x86_64 Linux nonprofiling build, words were eight bytes,
tables-next-to-code was enabled, `StgInfoTable` was 16 bytes, and `USE_LIBDW` was
zero. Stack.CCS intentionally defines `PROFILING` in its original `.hsc` input
to inspect that layout even with a nonprofiling compiler. Do not strip that
definition or claim every generated module describes a profiling runtime.
These constants do not make JVM guest objects compatible with native RTS layouts.

## Inventory prerequisites and research baseline

The historical research at THC `62e3400c5b889d3971cb4047709c408fd270255f`
included foreign-call metadata work (PR81). Base `f72f924` does **not** export
that structured metadata. Its source export and strict audit still work, but
`foreignDeclarations` is deliberately `null`, with
`foreignMetadataStatus: unavailable-exporter-prerequisite`. An empty list would
falsely suggest that no foreign calls exist. This change does not implement or
cherry-pick FFI support. With the metadata exporter present, inventory preserves
full declaration shapes and owner paths, not just symbol names. A plugin source
feature check on the actual captured plugin-build sources records that
prerequisite; it is not itself an ABI verification. Inventory verifies those
source hashes and the resulting binary, rather than inspecting an unrelated
current `Plugin.hs` next to an arbitrary prebuilt library.

The completed research snapshot had 112 successful library source modules, zero
failed modules, 42,046 supplied bindings, 5,041 reachable bindings, and no missing
Haskell globals. Remaining: 94 foreign occurrence IDs, 93 foreign declaration
shapes (92 unique symbols), 64 distinct unsupported primops, and representation,
literal, first-class primitive and IO-entry audit blockers. These historical
counts are not assertions about this base, future capabilities, or dynamic
execution frequencies. The snapshot includes dictionaries, alternate encodings,
errors, finalizers and stack inspection, not just the hot successful print path.

## Which native dependencies belong where?

All references below use the pinned official source, not reconstructed stubs.

| Boundary | Examples and qualification |
| --- | --- |
| Portable C candidates | Original `cbits/md5.c` and `cbits/strerror.c` now have bounded Sulong paths. Other libc allocation and POSIX read/write wrappers still require a correct host ABI, native buffers and lifetimes. |
| Mixed C/RTS helpers | `fdReady` in [inputReady.c](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/ghc-internal/cbits/inputReady.c#L154) uses polling plus RTS elapsed-time/error paths. `PrelIOUtils.c` contains portable locale code and RTS logging. Do not classify a whole translation unit as RTS-free from its happy path. |
| THC-owned runtime semantics | Stable pointers, weak finalizers, MVars, masking, asynchronous exceptions, capability counts, event-manager CAF stores and logical-thread errno cannot be supplied by an unrelated native GHC runtime's state. |
| Native memory-layout boundary | GMP wrappers need ByteArray payloads and pinned/marshalled lifetimes. [StackCloningDecoding.cmm](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/ghc-internal/cbits/StackCloningDecoding.cmm#L3) and `Stack.cmm` use native TSO, stack, capability and closure layouts; `foreign import prim` is not an ordinary C/Sulong call. |
| Optional debugging services | [lookupIPE](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/IPE.c#L183) looks up native info-table addresses. Enabled libdw walks native process/stack data, not THC guest frames. Original configured `USE_LIBDW=0` branches in `Libdw.c`/`LibdwPool.c` are genuine upstream disabled-feature behavior, not permission to invent success-returning externals. |

Safe and unsafe imports of `fdReady` share a symbol but have different guest
scheduling obligations. JSON arity can include zero-width State#; a C IO return
`(# State#, scalar #)` normally means one C scalar, not a C aggregate. Cmm prim
imports have different calling conventions again. Foreign address literals need
their own inventory: `&enabled_capabilities` is data, while `&libdwPoolRelease`
and `&backtraceFree` are finalizer function addresses, not ordinary call sites.

The real successful path is System.IO.putStrLn → Handle.Text.hPutStrLn →
buffer/encoding machinery → FD operations. `stdout` construction also requires
Handle locks, mutable references and finalization. Redirected short output may
remain buffered: native shutdown normally flushes standard handles through
[TopHandler](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/ghc-internal/src/GHC/Internal/TopHandler.hs#L253)
and RTS shutdown. A future bounded execution test should explicitly use ordinary
`putStrLn "hello" >> hFlush stdout`; that does not by itself implement process-exit
flush semantics. Scheduler/exception, native-address transport and real Handle
prerequisites must come first. This recipe does not claim they are implemented.
