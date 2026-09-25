<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# GHC library Core

THC needs function bodies, including private workers and `OPAQUE` definitions.
An ordinary installed `.hi` file need not contain them. GHC already has the
[option to retain every binding](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/phases.html#ghc-flag-fwrite-if-simplified-core):

```cabal
ghc-options: -fwrite-if-simplified-core
```

The [upstream patch](../compiler/patches/ghc-internal-simplified-core.patch)
adds that one option to the `ghc-internal` library. It does not change its
Haskell definitions or request extra inlining. Having the compiler ship this
data lets THC use that installation's library bodies instead of maintaining
copies of them for each compiler release.

## Check an installation

```sh
make check-ghc-core GHC=/path/to/ghc
```

The check reads the complete-Core field of installed interfaces through the
selected compiler's GHC API, in one process.
Recognizing the command-line flag is insufficient: `ghc-internal` must have
been built with it. Keep the matching compiler tools together; a `ghc-pkg`
from another installation must not supply the package being checked.

This is a capability check, not a declaration of GHC API compatibility.
The exporter currently supports GHC 9.14.1. Wiring complete installed Core into
the driver and removing the pinned-source fallback are migration work; the
normal build reports a missing capability without disabling that fallback. The explicit
`check-ghc-core` target fails when the required data is absent.

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
stubs or foreign files are explicitly rejected: the JSON exporter cannot
preserve those native build products. Ordinary foreign calls in Core still
require the runtime's normal support audit.

The result exposes the original module, `ModDetails`, `CoreProgram` and foreign
metadata. Hydration reads the raw interface before GHC's package cache strips
complete Core. It privately retains interface pragmas/source ticks and keeps
existing knot lookups for other modules. Use a fresh session retaining pragmas for dependency
loading; the loader does not repair previously discarded dependency unfoldings.
Cross-module home-package/hs-boot cycles are not yet an integration-tested use.

Serialization shares `THC.Plugin.serializePostTidyCore` with the late plugin,
including exact recursive groups, representations, existing CBV proofs and
optional `source-notes`/`unit-qualified` metadata. No source target is required;
missing source text stays absent. This API does not wire the driver cache, link
dependencies, or establish runtime support for an entire package.

The `thc-fixtures interface-core` control separately registers full and thin
synthetic packages, recovers an `OPAQUE` entry/private worker, checks identity,
way and foreign rejection, then supplies the recovered JSON and native results
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
result never substitutes inline unfoldings. The driver may make an explicit
source-fallback decision later; this change does not wire that policy/cache.

The fixture now feeds helper JSON through AST/bytecode execution and checks the
installed `CBVCoercionAudit` worker's real `idCbvMarks_maybe`/`entryStrict` against
the direct late-plugin export from its native compilation. No inferred marks
are allowed in this comparison.
It also probes the selected installation's actual `GHC.Internal.Char` interface:
stock thin interfaces must report missing capability, not wrong-unit failure.
If that installation carries full Core, the control requires successful loading
with the original wired owner. The installed interface is not copied or hashed.

## Build a patched compiler

These commands rebuild the release selected by an existing compiler. Use a
separate build directory and installation prefix. Install that release's
[GHC build prerequisites](https://gitlab.haskell.org/ghc/ghc/-/wikis/building/preparation)
first. GHC 9.14.1's `configure.ac` requires a bootstrap GHC of at least 9.6;
other releases may require a different bootstrap compiler.

From the THC repository, record its patch location and select the bootstrap:

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
patch --dry-run -p1 < "$THC_SOURCE/compiler/patches/ghc-internal-simplified-core.patch"
patch -p1 < "$THC_SOURCE/compiler/patches/ghc-internal-simplified-core.patch"

test -f configure || ./boot
GHC="$THC_BOOT_GHC" ./configure --prefix="$THC_GHC_PREFIX"
GHC="$THC_BOOT_GHC" ./hadrian/build -j4 --flavour=perf --docs=none \
  install --prefix="$THC_GHC_PREFIX"
```

This follows GHC's [Hadrian build and installation procedure](https://gitlab.haskell.org/ghc/ghc/-/blob/ghc-9.14.1-release/hadrian/README.md).
Use the release's published checksum or signature to verify the source archive.
Allow enough disk space for a compiler build; the source archive is much smaller
than the working set. The patch may already be present in a future release;
inspect a failed dry run before proceeding.

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

The patch applies to GHC 9.14.1 and upstream revision
`bf17f289eb6abf929917350e6c549474024539`. Hadrian passes the library's Cabal
`ghc-options` through its `hcOpts` arguments. The flag is also available in
GHC 9.6, the oldest bootstrap compiler accepted by the inspected release.

A small `-O2` probe with an `OPAQUE` exported entry and a private `NOINLINE`
worker acquired an `extra decls:` section containing both bodies. Its interface
grew from 1,313 to 1,516 bytes; its native object was byte-for-byte identical,
and both executables returned the same result. This does not measure the size
of a patched `ghc-internal` build. A complete compiler rebuild has not been
validated here.

This first patch covers `ghc-internal`. Other boot libraries still need their
own complete Core when their bodies are required. Ordinary project packages
can emit Core when THC builds them. GHC API changes and primop/RTS semantics
remain explicit compatibility work; retaining library Core removes one major
source of version-specific scaffolding, not those obligations.
