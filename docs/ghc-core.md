<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# GHC library Core

THC needs function bodies, including private workers and `OPAQUE` definitions.
An ordinary installed `.hi` file need not contain them. GHC already has the
[option to retain every binding](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/phases.html#ghc-flag-fwrite-if-simplified-core):

```cabal
ghc-options: -fwrite-if-simplified-core
```

Hadrian already accepts this option through its
[settings file](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/hadrian/doc/user-settings.md).
Append the supplied [configuration](../compiler/ghc-core.settings) to
`<build root>/hadrian.settings` (normally `_build/hadrian.settings`):

```text
*.*.ghc.hs.opts += -fwrite-if-simplified-core
```

This applies to Haskell compilation across packages and stages, including
`ghc-internal`, `base`, `template-haskell`, its lift/quasiquoter libraries, and
the compiler itself. It also retains Core in program and bootstrap interfaces;
C compilation and linking are unaffected. It does not require a source patch
or change Haskell definitions or inlining decisions. The aggregate interface-size
cost has not been measured.

An upstream release configuration can use the same setting. The optional
[Hadrian library patch](../compiler/patches/ghc-libraries-simplified-core.patch)
implements a narrower built-in default for library packages after bootstrap.
It covers `ghc-internal`, `base`, `template-haskell`, and every other library
without maintaining a package list. The patch is not needed with the settings
above.

## Check an installation

```sh
make check-ghc-core GHC=/path/to/ghc
make check-ghc-core CORE_PACKAGES='base containers text' GHC=/path/to/ghc
```

The default checks `ghc-internal` and `base`; `CORE_PACKAGES` selects other
installed packages. The check includes their registered transitive package
dependencies and reads each Haskell interface's complete-Core field through
the selected compiler's GHC API, in one process. Native-only registrations
have no Haskell interfaces to inspect.
Recognizing the command-line flag is insufficient: the consumed library must
have been built with it. Keep the matching compiler tools together; a
`ghc-pkg` from another installation must not supply the package being checked.
Run the check for each library whose bodies THC will load.

This is a capability check, not a declaration of GHC API compatibility.
The exporter currently supports GHC 9.14.1. The project driver already supports
`--installed-core required`, which resolves exact selected registrations and
acquires their complete interfaces through `thc-interface`. The default
`--installed-core pinned` remains a separate source-provider choice, not a
fallback after an interface error. The single-package `.cabal` path supports
only the pinned mode. See the [driver guide](driver.md) for acquisition, checked
ZIP caching and target-layout receipts. The normal build reports missing Core
capability without disabling pinned mode; the explicit `check-ghc-core` target
fails when the required data is absent.

## Library loading API

`THC.Interface` exposes the GHC 9.14.1 adapter independently of the generic
driver executable:

```haskell
loadInterfaceCore :: HscEnv -> Module -> FilePath -> IO (Maybe InterfaceCore)
interfaceCoreJSON :: [CommandLineOption] -> InterfaceCore -> IO String
```

Resolve the expected `Module` (including its exact package unit) and interface
path using the selected compiler session and package databases. `Nothing`
means a valid interface lacks complete Core; wrong module/unit identities,
way/version mismatches and malformed interfaces are errors. Nonempty foreign
stubs and foreign files are retained in [Core schema 2](interface-foreign.md),
including exact source and initializer/finalizer identities. Hydration does not
link native products or register foreign exports. The checked loader rejects
unlinked global lifecycle obligations even outside the entry's reachable
closure, and rejects reachable bindings owned by an unlinked archive module.
Verified native links have their own admission checks; unrelated archival
bindings do not grant executable status. Ordinary foreign calls still require
the runtime's normal support audit.

The result exposes the original module, `ModDetails`, `CoreProgram` and foreign
metadata. Installed acquisition serializes and forces each module's strict JSON
payload before loading the next interface. This releases its decoded Core tree
instead of retaining all such trees until the entire package is archived; the
GHC library alone contains over 800 interfaces. This does not change archive
bytes, cache identities, or interface validation.

Executable IDs use GHC's mangled occurrence spelling. In GHC 9.14, a record
selector such as `field` has a constructor-qualified namespace represented by
`$fld:Constructor:field`; it is distinct from another constructor's selector or
an ordinary exported alias named `field`. Display names remain unchanged. The
`thc-fixtures record-fields` controls cover two separately compiled modules at
both plugin boundaries and after interface hydration, with native comparisons
on both runtimes. Exporter identity changes invalidate older cached payloads.

Hydration reads the raw interface before GHC's package cache strips
complete Core. It privately retains interface pragmas/source ticks and keeps
existing knot lookups for other modules. Use a fresh session retaining pragmas for dependency
loading; the loader does not repair previously discarded dependency unfoldings.
Cross-module home-package/hs-boot cycles are not yet an integration-tested use.

GHC 9.14.1 writes this payload before `AddImplicitBinds` injects constructor
wrappers. The loader therefore also supplies missing, locally owned boxed-data
wrappers from the hydrated declarations' genuine `DataConWrapId` unfoldings,
with their original names, types and coercions. Existing binding groups remain
unchanged; wrappers are separate nonrecursive groups. This is not a fallback to
ordinary function unfoldings when complete Core is absent. Constructor workers
remain represented by constructor metadata, and newtypes are not injected.
An unpacked GADT wrapper is native-checked through both execution backends;
complete boot interfaces additionally check the original `$WTrType` and
`$WUnsafeRefl` bodies and strict audits.

Serialization shares `THC.Plugin.serializePostTidyCore` with the late plugin,
including exact recursive groups, representations, existing CBV proofs and
optional `source-notes`/`unit-qualified` metadata. No source target is required;
missing source text stays absent. The driver owns acquisition and cache
integration separately; this API does not link dependencies or establish runtime
support for an entire package.

The `thc-fixtures interface-core` control separately registers full and thin
synthetic packages, recovers an `OPAQUE` entry/private worker, checks identity,
way and lossless foreign archival, then supplies the recovered JSON and native results
to `InterfaceCoreNativeTest` for strict AST/bytecode execution.

## Selected-compiler helper

Build `exe:thc-interface` with the selected compiler, separately from the generic
driver's GHC-independent process. Invoke the helper directly (not `cabal run`,
whose build messages are not part of the protocol):

```sh
cabal build exe:thc-interface --with-compiler=/path/to/ghc
cabal list-bin exe:thc-interface
/path/to/thc-interface --libdir /path/from/selected-ghc-print-libdir \
  --unit exact-installed-unit-id --module Package.Module \
  --interface /path/to/Package/Module.hi --package-db /path/to/package.conf.d \
  --way vanilla --source-notes
```

`--libdir`, `--unit`, `--module` and `--interface` are required. Package databases
may be repeated in GHC stack order. The stack is explicitly the selected libdir's
global database plus those arguments: implicit user databases and package
environments are disabled. The helper does not discover packages, rebuild them,
or run guest code. The caller must select a helper built against the same GHC
API/installation as that libdir; changing libdir is not GHC API compatibility.
`--unit` is the exact registered package ID. The selected GHC `UnitState` maps it
to an interface owner (for example, a versioned `ghc-internal` registration to
the canonical `ghc-internal` owner) and must map back to that exact registration.
Core retains its original canonical identity; no version/hash stripping or
canonical-name alias is accepted in place of an exact registered ID.
Ways are `vanilla` (default), `dynamic`, or `profiling`; the interface header must
match the requested way. Only vanilla/dynamic synthetic packages are tested.

Except for `--help`, stdout is one UTF-8 JSON object with `schema: 1`:

| Exit | Status | Payload |
| --- | --- | --- |
| 0 | `loaded` | `core` contains the existing post-Tidy module JSON |
| 3 | `unavailable` | `capability: "complete-interface-core"`, unit/module/way/path; no Core |
| 1 | `error` | `category: "interface"` and a diagnostic message; no Core |
| 2 | `error` | `category: "usage"`, diagnostic and usage; no Core |

The complete serialized Core, including each character, is deeply forced before
any success bytes are emitted.
Cancellation is not converted to a missing-capability result. An unavailable
result never substitutes inline unfoldings. In the driver's installed-Core
required mode it is a capability failure; choosing the pinned source provider
is an explicit option, not a retry policy.

The fixture now feeds helper JSON through AST/bytecode execution and checks the
installed `CBVCoercionAudit` worker's real `idCbvMarks_maybe`/`entryStrict` against
the direct late-plugin export from its native compilation. No inferred marks
are allowed in this comparison.
It also probes the selected installation's actual `GHC.Internal.Char` interface:
stock thin interfaces must report missing capability, not wrong-unit failure.
If that installation carries full Core, the control requires successful loading
with the original wired owner. This control reads the installed interface in
place. The [driver's installed-Core cache](driver.md) separately fingerprints
GHC's full retained interface bytes and checks dependency providers and source
observations before avoiding hydration. GHC remains version-gated; compiler
executables are not hashed.

## Build a compiler with complete Core

These commands rebuild the release selected by an existing compiler. Use a
separate build directory and installation prefix. Install that release's
[GHC build prerequisites](https://gitlab.haskell.org/ghc/ghc/-/wikis/building/preparation)
first. GHC 9.14.1's `configure.ac` requires a bootstrap GHC of at least 9.6;
other releases may require a different bootstrap compiler.

From the THC repository, record its configuration location and select the bootstrap:

```sh
THC_SOURCE="$PWD"
THC_BOOT_GHC=$(command -v ghc)
THC_GHC_VERSION=$("$THC_BOOT_GHC" --numeric-version)
THC_GHC_PREFIX="$HOME/.local/ghc/$THC_GHC_VERSION-core"

mkdir ghc-core-build
cd ghc-core-build
curl -fLO "https://downloads.haskell.org/ghc/$THC_GHC_VERSION/ghc-$THC_GHC_VERSION-src.tar.xz"
tar -xf "ghc-$THC_GHC_VERSION-src.tar.xz"
cd "ghc-$THC_GHC_VERSION"
mkdir -p _build
cat "$THC_SOURCE/compiler/ghc-core.settings" >> _build/hadrian.settings

test -f configure || ./boot
GHC="$THC_BOOT_GHC" ./configure --prefix="$THC_GHC_PREFIX"
GHC="$THC_BOOT_GHC" ./hadrian/build -j4 --flavour=perf --docs=none \
  install --prefix="$THC_GHC_PREFIX"
```

This follows GHC's [Hadrian build and installation procedure](https://gitlab.haskell.org/ghc/ghc/-/blob/ghc-9.14.1-release/hadrian/README.md).
Use the release's published checksum or signature to verify the source archive.
Allow enough disk space for a compiler build; the source archive is much smaller
than the working set. For a nondefault build root, put `hadrian.settings` in that
directory instead. The append preserves any settings already there.

Then select the new installation consistently:

```sh
export PATH="$THC_GHC_PREFIX/bin:$PATH"
cd "$THC_SOURCE"
make check-ghc-core GHC="$THC_GHC_PREFIX/bin/ghc"
make GHC="$THC_GHC_PREFIX/bin/ghc"
```

Passing the same compiler to Cabal directly is `cabal build --with-compiler=/path/to/ghc`.
Changing the selected GHC must select its matching exporter build and package
database; cache entries also remain separated by target and compiler identity.

## Patch scope and checks

The Hadrian patch was dry-run against GHC's `ghc-9.14.1-release` source
(`902339d332fb4ce2b3c87dcac1ee6495d41ad886`) and upstream master at
`35bf6f4bcf06675565992725df62e837bc7788ce`. It selects Haskell
compilation of library packages after Stage0, for every built library way.
It includes GHC's compiler library as well as ordinary libraries; excluding
the compiler would make this an incomplete library-wide rule. The installed
release libraries are built with a later stage.

A small `-O2` probe with an `OPAQUE` exported entry and a private `NOINLINE`
worker acquired an `extra decls:` section containing both bodies. Its interface
grew from 1,313 to 1,516 bytes; its native object was byte-for-byte identical,
and both executables returned the same result. This does not measure the size
of a patched `ghc-internal` build. A complete compiler rebuild has not been
validated here.

The Hadrian flag only covers libraries built by that GHC source tree. Ordinary
project packages can emit Core when THC builds them; independently installed
packages require their own complete-Core build. `rts` C code and primop
semantics are separate from Haskell interface payloads. GHC API changes remain
explicit compatibility work; retaining library Core removes one source of
version-specific scaffolding, not those obligations.
