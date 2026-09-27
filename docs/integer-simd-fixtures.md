# Integer SIMD fixture tools

The `thc-fixtures` commands `int8x16`, `int16x8`, `word16x8`, and `word32x4`
share a Haskell producer. They keep the original fixture and native-oracle
Haskell sources unchanged, regenerate genuine pre/post-Tidy Core, invoke the
shared strict Core auditor, and retain exact root, lane and primitive-count
checks. The unsigned families also audit separate signed-metadata corruptions;
these never replace the original Core or native inputs.

Haskell computes the original ordered input domains and unbounded integer
model. Kotlin independently computes machine arithmetic and lane narrowing,
checks native/model rows, and exercises the retained encoding, product, lane
grid, permutation and malformed-row controls. Producer predicates reject
corrupted copies of genuine tuple and helper-call metadata on every preparation.
The four existing runtime test classes retain their exact first-installed-call
counts, target identities, validity and handoff cleanup assertions.
Runtime literal checks follow the lowered carrier contract: duplicate integral
annotations share `Long`, while the literal tag supplies narrowing. Wrong physical
carriers, signed literal tags at unsigned vector signatures, malformed aggregate
proofs, and vector shape mismatches remain rejection tests. The exporter audits
continue checking exact source-level representations independently.

Using the pinned environment and the checkout's build lease:

```sh
for family in int8x16 int16x8 word16x8 word32x4; do
  cabal run exe:thc-fixtures --offline -- "$family"
done
./gradlew --continue \
  testDefault --tests thc.runtime.IntegerSimdModelTest --tests 'thc.runtime.SimdInt8VectorTest' --tests 'thc.runtime.SimdInt16VectorTest' --tests 'thc.runtime.SimdWord16VectorTest' --tests 'thc.runtime.SimdWord32VectorTest' \
  testDense --tests thc.runtime.IntegerSimdModelTest --tests 'thc.runtime.SimdInt8VectorTest' --tests 'thc.runtime.SimdInt16VectorTest' --tests 'thc.runtime.SimdWord16VectorTest' --tests 'thc.runtime.SimdWord32VectorTest'
```

On ARM, append `--export-only` to each producer command, as `prepare-tests.sh`
does. This explicitly produces pre-Tidy/model-only evidence, removes an older
native oracle/provenance claim, and records null native counts. It never falls
back to that mode after a native failure. Both modes fingerprint their actual
source inputs, exports, audit reports, requests, model rows, and command receipts;
full mode additionally retains native output and the executable.

Experiment receipts belong to the exact source revisions and producer commands
that created them. Changing the current producer does not validate or rehash an
older artifact.
