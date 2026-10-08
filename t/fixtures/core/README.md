# Core regression inputs

These Haskell modules exercise compiler and runtime behavior. They are test
inputs, not public APIs. `NativeOracle.hs` and `LibraryOracle.hs`
are separate native executables; each declares `Main`
and is compiled independently.

- `bin/native-oracle.sh` builds the focused native oracle.
- `bin/prepare-corpus.py` exports the groups in [coverage.json](coverage.json)
  and records their native results for `CoverageCorpusTest`.
- `cabal run exe:thc-fixtures --offline -- COMMAND` prepares individual
  memory, floating-point, thread and interpreter fixtures.

`Fixtures` imports `THC.Prim.Test` from the private `thc:lib:tests` component.
That deliberately named test module is not part of the public runtime API.
See [coverage](../../../docs/coverage.md) for running the corpus.
