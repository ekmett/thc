# Format signed integers, words and lists with the original Show instances

The `ShowWordListAudit` example uses GHC's ordinary public `show` implementation.
It observes every output character as well as a checksum. Singleton and
multi-element lists exercise the public signed `Int` formatter, including both
machine extrema, signs, decimal boundaries and small values from -20 through 20.
Unsigned words additionally cover the full 64-bit range. These examples need
complete original Show and CString dependencies; THC does not substitute Java
formatting for a missing Haskell worker.

```sh
python3 bin/prepare-show-word-list.py
./gradlew testDefault --tests thc.runtime.ShowWordListTest \
  testDense --tests thc.runtime.ShowWordListTest
```

Preparation builds the native GHC oracle, exports Core and checks the complete
reachable closure. For your own program, use the [driver](driver.md) with
[complete installed Core](ghc-core.md). Numeric primitive support alone does not
supply every formatting or exception dependency.
