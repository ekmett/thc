# `lens`: public API examples and original upstream tests

This recipe uses the real `lens` 5.3.6 library, not a substitute implementation.
It includes fourteen checks in
`LensExamples.hs`: generated record lenses, nested and indexed traversals,
matching and nonmatching prisms, state updates, folds, map insertion, empty
focuses, `partsOf`, filtering and a type-changing traversal. The opaque
input-dependent check function keeps these applications in exported Core.

Use the complete-Core GHC 9.14.1 installation, Cabal 3.16 and matching Graal
runtime described in [the driver guide](../../../../docs/driver.md). The Cabal index
state is pinned in `cabal.project`. Acquire the original source archive from this
directory:

```sh
curl -L https://hackage.haskell.org/package/lens-5.3.6/lens-5.3.6.tar.gz \
  -o lens-5.3.6.tar.gz
echo 'd345dcf1fda4d4a127b84d42638b62f783cf52750bd8fd14ab1637510c1023c2  lens-5.3.6.tar.gz' | sha256sum -c -
mkdir upstream-lens
tar -xzf lens-5.3.6.tar.gz --strip-components=1 -C upstream-lens
```

The corresponding upstream release tag `v5.3.6` identifies commit
[`b0a77f7c92acc3038b592a67242ca716d3636c6a`](https://github.com/ekmett/lens/tree/b0a77f7c92acc3038b592a67242ca716d3636c6a).
Retain the archive's license and unmodified sources. The library itself is the
pinned Cabal dependency; `upstream-lens` supplies its original test sources.

`lens-hunit`, `lens-properties` and `lens-templates` wrap the unchanged upstream
test modules as executables, retaining the original capture fixture layout.
The driver also accepts original exitcode test-component targets directly.
Their source directories, dependencies and options follow the original test
stanzas. The template suite's substantial checks run during native compilation;
its runtime entry only prints a confirmation. This does not claim Template
Haskell executes on THC. The dummy upstream doctest component is not a test
runner and is not wrapped here.

Native baselines:

```sh
cabal build all --with-compiler="$GHC" --with-hc-pkg="$GHC_PKG" -j2
cabal run lens-public-api
cabal run lens-templates
cabal run lens-hunit -- --num-threads=1 --color=never +RTS -N1 -RTS
cabal run lens-properties -- --num-threads=1 --color=never \
  --quickcheck-replay=20260926 +RTS -N1 -RTS
```

For original-Core guest execution, build the THC driver, interface exporter,
plugin and runtime from the selected THC checkout. Set absolute `THC_ROOT`,
`GHC_SOURCE`, `GHC` and `GHC_PKG` paths, then run:

```sh
thc run lens-thc-check:exe:lens-public-api \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/lens/public-api" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE"
thc run lens-thc-check:exe:lens-hunit \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/lens/hunit" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" \
  -- --num-threads=1 --color=never
thc run lens-thc-check:exe:lens-properties \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/lens/properties" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" \
  -- --num-threads=1 --color=never --quickcheck-replay=20260926
```
