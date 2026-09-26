# `ad`: original tests and sharing-sensitive differentiation

Use the pinned full-Core GHC 9.14.1 toolchain and Cabal 3.16.1. From this
directory, acquire the unchanged upstream source:

```sh
git clone https://github.com/ekmett/ad.git upstream-ad
git -C upstream-ad checkout --detach 75a3efa90486e6eee882355af5bd3e7ab63b77d3
cabal build all
cabal test ad:regression --test-show-details=direct \
  --test-options='--quickcheck-replay=20260926 --num-threads=1'
cabal run ad-kahn
```

This is `ad` 4.5.6. Its default `ffi` flag is disabled; the library's optional
native tape implementation must not be treated as an active C dependency.
`ad-regression` wraps the exact upstream `Regression.hs` and `AdditionalTests.hs`
as an executable without editing them. The native baseline is 89 passing
upstream tests plus the ten checks in `ADKahn.hs`.

`ADKahn.hs` exercises differentiation, gradients, Jacobians and Hessians through
the public Kahn API, including its specialized Double mode. The five shared DAG
checks have depths 0, 1, 8, 20 and 32: repeated `let`-bound subexpressions must
remain shared when `data-reify` discovers graph nodes using stable names.
Expected derivatives are independently the corresponding powers of two.

With the built Haskell driver on PATH, select the actual THC checkout and GHC
source tree (the placeholders below are paths, not environment settings):

```sh
thc run . --exe ad-thc-check:exe:ad-kahn \
  --thc-root /path/to/thc --dist-dir dist-thc/kahn \
  --installed-core required --ghc-source /path/to/ghc-9.14.1
thc run . --exe ad-thc-check:exe:ad-regression \
  --thc-root /path/to/thc --dist-dir dist-thc/regression \
  --installed-core required --ghc-source /path/to/ghc-9.14.1 \
  -- --quickcheck-replay=20260926 --num-threads=1
```

Guest execution is under investigation; native success is not a THC success
claim. The first guest acquisition exposed and now has a fix for treating
disabled conditional C sources as mandatory. Further guest frontiers are
recorded as they are reached rather than bypassing audits or changing upstream
tests.
