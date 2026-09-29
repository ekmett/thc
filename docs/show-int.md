# Format Int with the original Show instance

The `ShowIntAudit` example uses GHC's ordinary public `show` implementation.
It observes every output character as well as a checksum. These examples need
complete original Show and CString dependencies; THC does not substitute Java
formatting for a missing Haskell worker.

```sh
python3 bin/prepare-show-int.py
./gradlew testDefault --tests thc.runtime.ShowIntTest \
  testDense --tests thc.runtime.ShowIntTest
```

Preparation builds the native GHC oracle, exports Core and checks the complete
reachable closure. For your own program, use the [driver](driver.md) with
[complete installed Core](ghc-core.md). Numeric primitive support alone does not
supply every formatting or exception dependency.
