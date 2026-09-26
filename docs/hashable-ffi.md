# Hashable package FFI regression

The `hashable-ffi` fixture uses original Hackage `hashable-1.5.1.0`, not a
rewritten implementation or integer-only stand-in. Cabal pins
`random-initial-seed=False` and `arch-native=False`; the preparer checks the
resolved version and both flags. Native expected values come from the same
Cabal plan and package build used for Core acquisition. They are test evidence,
not a portable or persistent hash specification.

The [probe library](../test/fixtures/run-hashable-ffi/src/HashableProbe.hs)
exports five scalar entry points around genuine `hashWithSalt` instances:
strict `Text`, strict `ByteString`, `ShortByteString`, lazy `Text` and lazy
`ByteString`. Each takes a dynamic salt and input selector. The 300 native rows
cover five salts (including both signed extremes), empty inputs, short/medium/
long boundaries through 4,097 elements, slices with nonzero backing offsets,
Unicode, embedded NUL and high-bit bytes. Lazy chunking exercises original
XXH3 state allocation, initialization, reset, repeated updates and digest.
That state requires aligned storage and must survive across separate FFI calls.

With the pinned complete-Core GHC 9.14.1, matching source checkout, configured
Clang and installed THC runtime:

```sh
export THC_INSTALLED_CORE_GHC_SOURCE=/path/to/ghc-9.14.1
cabal run exe:thc-fixtures -- hashable-ffi
./gradlew --max-workers=2 --continue \
  hashableFfiFullCoreDefault hashableFfiFullCoreDense
```

`THC_TEST_RUNTIME` can select an already installed runtime launcher. The
preparer uses ordinary `thc run`, requires the strict audit and complete native
stdout agreement, and retains unmodified, hash-checked dependency bundles.
Logs, command records, native observations and source/artifact hashes are under
`build/hashable-ffi`. No successful manifest is left after a failed preparation.

The dedicated JVM group loads the real probe roots from that manifest in both
backends, without substituting GHC's original IO startup or running the AST
backend through its unrelated process-signal limitation. It requires the real
reachable emitted CAPI calls for each instance, including mutable/immutable
byte-array and pointer arguments. After interpreted comparisons, it requests
compilation and checks the very next invocation and every remaining compiled
comparison, with no settling call, retry or recompile. Native permission is
explicit. Both handoff modes also check scratch storage is released.
Each backend/instance pair is an independent dynamic test, so one missing
dependency does not suppress the other instances. The full original boot-Core
manifest needs an 8 GiB test heap; 4 GiB exhausted the heap during loading.

These tests require the general `thc-package-c-ffi-v1` acquisition/runtime
implementation. A native-only oracle run or a successful Kotlin compilation
is not an end-to-end success; missing fixture preparation is a hard failure,
not a skipped test.

## Current original-Core checkpoint

Direct captured-Core checks match all 180 native byte-oriented observations
(`ByteString`, `ShortByteString`, lazy `ByteString`) in both AST and bytecode,
with both handoff modes. Each check also repeats the entire matrix after
compilation and requires an increased compiled-entry count on the very first
and every later call. The strict audit accepts these roots with no missing
globals or unsupported calls.

Those checks exposed and fixed two runtime bugs: an empty `ByteString`'s
zero-length null-pointer copy into a `ShortByteString`, and per-call ABI-set
construction that made the compiler recursively inline Java collection/error
machinery. Neither fix changes compiler budgets or Hashable's source.

The complete five-instance fixture is not yet a passing application claim.
Both original `Text` probes still require the installed text package's
`_hs_text_measure_off` C helper for public slicing/chunking. They remain in the
fixture; no successful full manifest is written while that dependency is
missing.
