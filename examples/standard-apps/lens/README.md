# `lens`: public API examples and original upstream tests

This recipe uses the real `lens` 5.3.6 library, not a substitute implementation.
It extends the previously tested `_1`/`over`/`view` smoke with fourteen checks in
`LensExamples.hs`: generated record lenses, nested and indexed traversals,
matching and nonmatching prisms, state updates, folds, map insertion, empty
focuses, `partsOf`, filtering and a type-changing traversal. The opaque
input-dependent check function keeps these applications in exported Core.

Use the complete-Core GHC 9.14.1 installation, Cabal 3.16 and matching Graal
runtime described in [the driver guide](../../../docs/driver.md). The Cabal index
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
test modules as executables because `thc run` selects executable components.
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

On Linux x86-64 with GHC 9.14.1, these passed all fourteen example checks,
55 upstream HUnit tests and 25 upstream properties with 100 successful cases
each (seed `20260926`). The template build and confirmation also passed.
The native test sources have these SHA-256 identities:

| Source below `upstream-lens` | SHA-256 |
| --- | --- |
| `tests/hunit.hs` | `91c7ae0e13671b8a55e0349976fb3e588f1bcbcf707b548c3a09b1fadbcc02e5` |
| `tests/properties.hs` | `161cc8f75fd044039f94f16c67a551119a25ff4bad760c4d795167f875d838c1` |
| `tests/templates.hs` | `1328b4d557e8f4182b823e45c402c20b44f64ee5dec5ce22fe362034d0fe0ecb` |
| `lens-properties/src/Control/Lens/Properties.hs` | `0f45cc70e3845e4d1ea2eabd0d4ef0488e9b9f36a5f77ab3e13d20c50a6bc414` |

For original-Core guest execution, build the THC driver, interface exporter,
plugin and runtime from the selected THC checkout. Set absolute `THC_ROOT`,
`GHC_SOURCE`, `GHC` and `GHC_PKG` paths, then run:

```sh
thc run . --exe lens-thc-check:exe:lens-public-api \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/lens/public-api" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE"
thc run . --exe lens-thc-check:exe:lens-hunit \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/lens/hunit" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" \
  -- --num-threads=1 --color=never
thc run . --exe lens-thc-check:exe:lens-properties \
  --thc-root "$THC_ROOT" --dist-dir "$THC_ROOT/build/lens/properties" \
  --with-ghc "$GHC" --with-ghc-pkg "$GHC_PKG" \
  --installed-core required --ghc-source "$GHC_SOURCE" \
  -- --num-threads=1 --color=never --quickcheck-replay=20260926
```

Guest execution is being checked. Native success alone is not a THC success
claim; preserve `audit.json`, `packages.json`, native receipts and Core ZIPs
when investigating an acquisition, admission or runtime failure. The ordinary
driver executes IO entries in the interpreter, not as a compiled-entry proof.
