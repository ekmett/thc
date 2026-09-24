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
The exporter currently supports GHC 9.14.1. Reading complete installed Core
and removing the pinned-source fallback are migration work; the normal build
reports a missing capability without disabling that fallback. The explicit
`check-ghc-core` target fails when the required data is absent.

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
