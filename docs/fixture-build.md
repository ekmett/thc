# Fixture file graph

CMake generates the Ninja graph; Cabal compiles Haskell tools and Gradle builds
and tests the application. CMake 3.24+ and Ninja are required in addition to the
pinned compiler described in the README.

```sh
cmake -S . -B build/fixtures -G Ninja -DGHC=/path/to/ghc-9.14.1
cmake --build build/fixtures --parallel 2 --target fixture-runtime-core-native
make fixtures TESTS=thc.IntegerPrimopsTest
```

The admitted groups are `compact-model`, `signed-narrow-primops`, `bit-primops`,
`integer-primops`, `explicit64-primops`, `runtime-core-native`, `source-core` and
`strict-fields`, `int-arrays`, `int8-arrays`, `int16-arrays`, `int32-arrays`,
`double-arrays`, `float-word-arrays`, `pinned-pointer-cells`, `integer-completion`,
`word-floating`, `tuple-arithmetic`, `scalar-bitcasts`, `floating-remainder`,
`fused-floating`, `small-arrays`, `managed-address-reads`, `wide-char-address`,
`scalar-memory-utilities`, `thread-scheduling`, `thread-label`, `weak-explicit`,
`stable-names`, `explicit64-arrays`, `floating-address`, `floating-byte-offset`,
`atomic-address`, `unaligned-scalar-memory`, `address-array-copy`, `deep-evaluation`,
`mask-functions`, `thread-status`, `uncaught-self`, `live-async`, `vector-api`,
`truffle-strings`, `thread-async`, `native-addresses`, `simd-int64x2`,
`simd-int32x4`, `simd-floatx4`, `simd-doublex2`, `simd-calls`, `simd-floatx4-fma`,
`simd-wide-floating-fma`, `io-main-pap`, `simd-wide-arrays`, `simd128-arrays`,
`simd-arithmetic`, `pinned-addresses`, `record-fields`, `rts-diagnostics`,
`rts-shutdown`, `original-errno`, `ghc-bco`, `closure-inspection`, `cstring`,
`thread-inventory`, `process-lifecycle-native`, `delimited-continuations`, `hint-trace`,
`core-continuation`, `large-literal-cases`, `original-termios`, `original-rts-locks`
and `rubbish-literals`. Each has a `fixture-<group>`
target. `make fixtures` requires an
exact test selector; an unmigrated or quarantined selection fails before running
any producer. The older CI preparation path has not yet been migrated.

`CMakeLists.txt` includes the file rules in `cmake/`. Arithmetic exports declare
both the fixture CBD and interface-closure CBD as outputs of the same invocation.
For the arithmetic families, the native executable, generated driver, oracle TSV
and manifest have their own edges. Each scalar-array family currently uses one
command with all its named outputs; its native compilation and pre/post exports
share the already-built tools. The shared runtime oracle compiles without unused simplifier dumps. Each
Core compilation has its own object directory. No rule consumes an output-directory
listing or needs an empty directory. Ninja's file and command dependencies own
freshness; these rules do not use the old directory-hash receipts as a cache.

The Cabal plan determines executable paths and plugin dependency units. One rule
builds the four shared tools and owns their Cabal plan and package cache. Another
publishes the plugin library, private package cache and every non-boot registration.
For a missing Cabal product, the rule removes only that component's build and
registration memo before invoking Cabal; GHC reuses its objects and relinks. The
rule checks that every promised product exists before succeeding. Publication records the actual external registrations
and dynamic libraries in a Ninja depfile. The Java synthetic-CBD encoder's path
sidecar also has one writer. Model-writing tests and their fixture groups depend
on that owner explicitly; the Java helper no longer falls back to `cabal list-bin`.
Two formerly misclassified fixture-free classes now select the encoder. Gradle is not invoked by fixture generation. Native/Core compilation uses the
selected global package database. Its interfaces, native libraries, registrations,
compiler settings and available configured compiler/linker executables are file
inputs, so replacing Core under the same compiler version invalidates the graph.
The configured search path is captured, with ambient GHC package environments and
`GHC_PACKAGE_PATH` excluded. The published plugin database is an explicit input.

This is an incremental migration of the [audited flows](fixture-inputs.log).
[Quarantined producers](fixture-quarantine.log) have no graph targets. In particular,
installed-Core/native-package producers still need their acquisition products
represented before admission; neither the pinned-source provider nor complete
installed Core is replaced or disabled by these rules. No claims about those
providers or runtime test results follow from successfully generating fixtures.

Local verification uses macOS arm64, GHC 9.14.1 and GraalVM 25.3.4.1 / JDK 25.
There are 72 graph targets covering 113 mapped JUnit classes, 46 quarantined
groups and 20 groups still awaiting file rules. The native process target is
Linux x86_64 only; its two consumers do not select retained-Core acquisition.
Gradle no longer builds these process controls for every unrelated test.

Boxed and primitive foreign-provenance controls use in-memory models. Their
optional archive-count fixtures and the global Gradle manifest probe are removed;
28 metadata rejection checks passed across both handoff modes in a 12-second run.

RTS locks passed all six executions in both handoff modes in 17 seconds. All
33 consumed artifacts had matching hashes and declared graph writers. Its unused
template snapshot is removed. Deleting oracle.json rebuilt its owner in 2.00 seconds;
the immediate repeat did no work in 0.46 seconds.

Rubbish literals use GHC's typed constructors and 203 native continuation results;
the duplicate installed-event-manager inventory is removed. Tests allow different
filler bits and compiled call counts while retaining first-call, shape, ownership
and rejection checks; all 18 executions passed across both handoff modes in 27 seconds.
Its 30 consumed artifacts have graph writers, and an unchanged
repeat did no work in 0.37 seconds. AArch64 native generation requires LLVM's `opt`
and `llc` on the configured PATH; this run used the installed LLVM 18 tools.

- Eight changed consumer classes passed all 88 executions across default/dense
  handoffs in 5m15s. These cover CString, errno, BCOs, closure inspection, thread
  inventory and three SIMD groups. Both backends run within those classes.
  BCO was the largest class: 46.4 seconds in default mode.
- `make test-modes TESTS=thc.runtime.BigNatLiteralTest` passed another ten
  executions in 33 seconds with the declared model encoder. Make and direct
  Gradle now share the standard dependency cache unless explicitly overridden.
- Continuation and hint/trace consumers passed 90 executions across both handoff
  modes in 1m39s, including case-arm consumers. The native trace-lifetime control
  skipped once per mode because it requires Linux x86_64.
  Both new targets returned to no work; their 45 receipt artifacts had graph writers.
  Continuation audits dropped from 35 to three and hint/trace from 12 to two.
  Hint/trace no longer generates or hashes the native GHC eventlog.
- The ordinary continuation/literal split passed 86 executions in both handoff
  modes in 19.6 seconds. Deleting the lazy callback oracle rebuilt its owner in
  3.06 seconds and left the independent literal fixture untouched; the next run
  did no work in 0.40 seconds. Their 29 declared products exist.
- The standalone libc `strerror` harness and its package-Core dependency were
  removed. General native buffer/lifetime and typed package-link checks passed
  46 executions across both modes in 15.5 seconds. Its retired generated files
  freed 12.9 MiB. Saved-termios has an explicit Linux x86_64 graph; macOS
  generated its unsupported receipt and returned to no work. Its Linux products
  and runtime behavior remain unverified here.
- Descriptor append behavior is fixture-free; its old `fcntl` producer and 28
  audits are gone. Descriptor ownership and host ABI checks passed in both modes;
  the preserved append control still requires Linux x86_64. The nightly foreign-
  exception job is disabled while its Make prerequisite is quarantined.
- Thread inventory has separate native object directories, two nine-entry
  audits instead of 18, and no source-shape/root-count predictions. Its first
  compiled calls, interpreter-bypass negative control, callback masking and
  context-close cancellation passed in both handoff modes.
- BCO audits use two processes instead of 70; closure inspection uses one
  instead of nine. SIMD memory/shuffle uses three instead of 216 on ARM
  (four instead of 252 on Linux), covering the same entry sets.
- All 65 targets before continuation admission generated on macOS in 3m12s.
  With the graph unchanged, the repeat did no work in 0.45 seconds. All 1,324
  artifact hashes across 59 receipts matched files with declared graph writers.
- Missing CBD siblings, oracle files and the encoder sidecar rebuilt through
  their owning rules. Unrelated extra CBD files did not enter the inputs.
  Deleting the interface-reader executable rebuilt it and its record-field
  consumer in 8.16 seconds; the immediate repeat did no work in 0.41 seconds.
  Selected rebuilds returned to no work. Every checked receipt artifact had
  a declared graph writer; the CString overlay's files also have writers.
- The 102 change-selection checks, 51 fixture-selection checks and 23 plugin
  checks passed. Record-field
  hydration regenerated its own interfaces with matching pre/post native rows.
- Pinned-address rejection controls passed without leaving scratch catalogues.
  Removed 48 unreferenced catalogues (9.8 MiB), retaining named reports and logs.

The focused JVM results do not establish that all 111 mapped classes pass.
Linux generation/execution of the newly migrated rules, native Windows graph
support and migration of the CI entry point remain open. Successful local
fixture generation is not a claim that CI is green.
