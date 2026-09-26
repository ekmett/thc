# FloatX16 and DoubleX8 fused arithmetic

The four fused operations `fmadd`, `fmsub`, `fnmadd` and `fnmsub` now accept
exact `FloatX16#` or `DoubleX8#` operands on the AST and bytecode backends.
Each takes three identical vector shapes and returns that shape. Wrong widths,
lane types, arities and lifted operands are rejected by the Core contract.
The eight primops remain **partial** entries in the coverage checklist.

The formulas are `x*y+z`, `x*y-z`, `(-x)*y+z` and `(-x)*y-z`, respectively.
Operand signs are selected before a single fused rounding. This matters for
cancellation and signed zero. The runtime uses the existing owned primitive
lane carriers and transient Java Vector API values. Floating behavior follows
Java semantics; NaN payload selection is unspecified.

`SimdWideFloatFma.hs` exports genuine GHC 9.14.1 Core with 512-bit primops,
vector workers and partially applied vector calls. Its separate native Haskell
oracle uses scalar GHC FMA primops with the same lane permutations. Preparation
requires 2,112 observations across all eight operations, every lane and 22 raw
input triples per shape, including cancellation, signed zero, subnormals,
infinities and NaNs. Both strict Core audits must pass, and hashes bind the
sources, Core, audits and native observations.

The fixture deliberately records only the pre-Tidy Core boundary,
`oracleKind: native-ghc-scalar-fma-lanes` and `nativeVectorParity: false`.
It executes scalar FMA on AArch64 or Linux x86_64 with FMA support, so AVX512
hardware is unnecessary. Native 512-bit instruction parity remains unproved;
there is no JVM hardware-SIMD, retained-graph or performance claim.

`SimdWideFloatFmaTest` compares each native observation with the Java scalar
model and genuine Core execution on both backends. It checks interpreted calls,
first installed compiled scalar entries and compiled vector workers, including
the exact 48-float or 24-double argument layout, retained compiled targets and
released handoff state. Finite results and signed zero use raw bits; NaNs are
compared by class.

The Linux validation passed all 14 focused tests across the default and dense
handoff modes, including both compiled backends and the narrower FMA regression.
To repeat the wide checks with the project's configured Graal JDK:

```sh
cabal run exe:thc-fixtures --offline -- simd-wide-floating-fma
./gradlew test --tests thc.runtime.SimdWideFloatFmaTest
```

The shared harness regression also uses the existing narrower FMA fixture:

```sh
cabal run exe:thc-fixtures --offline -- simd-floatx4-fma
./gradlew test --tests thc.runtime.SimdFloatFmaTest
```
