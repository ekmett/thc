# Stable names

Both backends implement `makeStableName#` and `stableNameToInt#`. Names describe
object identity without evaluating their referent, including boxed unlifted
objects such as mutable references. Repeated naming of the same object yields
the same token and hash. The original GHC `eqStableName#` implementation uses
the existing pointer-identity primitive.

Names and their weak identity lookup belong to one THC context. Holding a name
does not retain its referent. This is identity, not structural equality: the
lookup never calls a guest or host object's equality or hash function. Evaluating
a thunk may change the identity subsequently named, as permitted by GHC's stable
name contract. Hash numbers are target-local and are not native-GHC addresses or
portable serialized identities.

`examples/StableNames.hs` covers lifted sharing, unlifted sharing, distinct mutable
references and naming an unevaluated bottom. Its `Int#` entry wrappers match the
development runner's scalar ABI. The Haskell fixture producer records 20 native
observations, pre/post Core and eight strict audits. `StableNamesTest` runs the
original entries through both backends, checks their first installed calls and
context cleanup, and tests concurrent naming and cross-context rejection.

```sh
cabal run exe:thc-fixtures -- stable-names
./gradlew --continue testDefault --tests thc.runtime.StableNamesTest \
  testDense --tests thc.runtime.StableNamesTest
```
