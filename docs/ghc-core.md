<!-- SPDX-FileCopyrightText: 2026 Edward Kmett -->
<!-- SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause -->

# GHC library Core

THC needs function bodies, including private workers and `OPAQUE` definitions.
An ordinary installed `.hi` file need not contain them. GHC already has the
[option to retain every binding](https://downloads.haskell.org/ghc/9.14.1/docs/users_guide/phases.html#ghc-flag-fwrite-if-simplified-core):

```cabal
ghc-options: -fwrite-if-simplified-core
```

The [Hadrian library patch](../compiler/patches/ghc-libraries-simplified-core.patch)
adds that option when GHC compiles any library package after the bootstrap
stage. This includes `ghc-internal`, `base`, and other shipped Haskell libraries
without maintaining a package list. It does not change their definitions or
request extra inlining. Programs, C compilation, and the bootstrap stage are
unaffected. The compiler's own `ghc` library is included; its interface-size
cost has not been measured. The earlier
[single-library patch](../compiler/patches/ghc-internal-simplified-core.patch)
remains available for an installation that only needs `ghc-internal` Core.

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
missing source text stays absent. This API does not add a CLI, wire the driver
cache, link dependencies, or establish runtime support for an entire package.

The `thc-fixtures interface-core` control separately registers full and thin
synthetic packages, recovers an `OPAQUE` entry/private worker, checks identity,
way and foreign rejection, then supplies the recovered JSON and native results
to `InterfaceCoreNativeTest` for strict AST/bytecode execution.

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
patch --dry-run -p1 < "$THC_SOURCE/compiler/patches/ghc-libraries-simplified-core.patch"
patch -p1 < "$THC_SOURCE/compiler/patches/ghc-libraries-simplified-core.patch"

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

The Hadrian patch was dry-run against GHC's `ghc-9.14.1-release` source
(`902339d332fb4ce2b3c87dcac1ee6495d41ad886`) and upstream master at
`35bf6f4bcf06675565992725df62e837bc7788ce`. It selects Haskell
compilation of library packages after Stage0, for every built library way.
It includes GHC's compiler library as well as ordinary libraries; excluding
the compiler would make this an incomplete library-wide rule. The installed
release libraries are built with a later stage. The earlier one-line Cabal
patch remains a smaller alternative, not a prerequisite for this patch.

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
