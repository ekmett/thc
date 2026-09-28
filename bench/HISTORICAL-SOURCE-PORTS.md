# Historical source ports

The Java/Groovy counterparts below are later source translations. **They were
not inputs to the archived measurements.** Existing manifests, result files,
hashes and frozen runtime identities continue to describe their original
Kotlin inputs. They have not been rewritten to describe Java results.

All five original sources are immutable Git blobs retained in the history at
`7ac51f9df79996badfdc82e74d5a865fecdf8123`. To inspect an original, use
`git show <blob>`; to reproduce an old harness that expects its original paths,
use an isolated checkout of that revision. Do not substitute the translations
for inputs covered by historical hashes.

| Original path | Git blob | Original SHA-256 |
| --- | --- | --- |
| `bench/experiments/doublex2-bytearray/evidence-x86_64/validation/native-focused-stages/SimdDoubleByteArrayTest.kt` | `987af6d1b556eb73362439ab688d8410dedda03a` | `1fbe93daa65300a411152c4d959c7f9f793f931208dad02c639093f2ed7910d0` |
| `bench/results/boxed-values/validation/fixture-cleanup/src/test/kotlin/thc/runtime/BoxedValueCacheTest.kt` | `a41894f05c303a70a814b7245d7215ac10fb5597` | `f90200ab8404c167c0b295a83e125711888f39b3fe47ec8adad907d8662d0b55` |
| `bench/results/castlemeadow-caller-diagnostics/tools/capture/settings.gradle.kts` | `acab39d55723b48b572ecbd8b6374bb0ef78a235` | `5b0d58cf2560ea8db80bdddca3702036b137466c88d691714566d2c62d6b88e5` |
| `bench/results/class-owned-layouts/prototype/build.gradle.kts` | `0aad1b3d6b09ef6dbd9b6b85d9c194233f6ec5a7` | `35997180f7ef35ddcf3fabe7bf9203356bfcde86c80a32b9244336f82e2fa495` |
| `bench/results/class-owned-layouts/prototype/settings.gradle.kts` | `7d4c69782abc40858a592590dcee9138152b3e8e` | `e380485e3d8c6b1fa35b73cd468f583be0cb864a4f0b2c0e5afc54348d0811a9` |

The corresponding Java tests preserve the historical assertions, including
historical unsupported-feature expectations. They are archival controls, not
current feature-support claims, and are not silently included in current test
discovery. Their primitive oracles and native evidence checks remain distinct
from current implementation results.

The prototype's Groovy build is the equivalent Java-source configuration with
Java annotation processing, Java toolchain25, and authored `thc.Main`/`thc.Probe`
entrypoints. It is not a claim that the historical prototype was rebuilt or
remeasured with those settings. The original KAPT configuration remains
recoverable through its immutable blob above. The isolated capture settings
file is still only a root marker; it does not perform a build.
