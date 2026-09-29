# Integer SIMD fixture tools

The shared Haskell producer exports original Core and checks native scalar-lane
models for the byte/short/int vector families. These are contributor checks;
[SIMD families](simd-families.md) describes the user-visible operations.

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
does. This explicitly produces pre-Tidy/model-only evidence, records null native counts. It never falls
back to that mode after a native failure. Both modes fingerprint their actual
source inputs, exports, audit reports, requests, model rows, and command receipts;
full mode additionally retains native output and the executable.
