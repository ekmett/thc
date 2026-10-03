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
`core-continuation`, `large-literal-cases`, `original-termios`, `original-rts-locks`,
`rubbish-literals`, `original-tcgetattr`, `original-tcsetattr`, `float-decode`
and `process-signals`, `unix-libc`, `exception-result-layouts`,
`scalar-exception-results`, `original-fd-ready`, `address-fields`,
`original-stdio-truncate`. Each has a `fixture-<group>`
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
There are 82 graph targets covering 123 mapped JUnit classes, 46 quarantined
groups and eight groups still awaiting file rules. The native process target is
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

Terminal get/set targets own their Linux PTY servers, Core, audits and observations.
On macOS both emitted explicit unsupported receipts and returned to no work; Linux
generation and runtime execution remain unverified here. Unsupported terminal targets
do not publish an unused plugin database.

Floating decomposition passed ten executions across both handoff modes in 32 seconds.
All 326 declared files existed and all 29 consumed artifacts had matching hashes and
graph writers. On the settled graph, deleting the native oracle rebuilt only its
fixture in 3.36 seconds, kept the bignum dependency untouched and returned to no work
in 0.41 seconds. Its audits dropped from 16 to two; obsolete bignum copies freed 14.4 MiB.

Process signals passed 36 executions with four platform skips across both handoff
modes through `make test-modes TESTS=thc.runtime.ProcessSignalsTest` (Gradle: seven
seconds). CMake owns the isolated C control with assertions enabled in Release;
the Haskell producer consumes that executable. Linux capture generation remains
unverified here. Fork-failure and Loom controls select no generated fixtures and
their `make fixtures` commands invoke no toolchain. The 49 fixture-selection
checks and the manifest ownership check passed.

Unix descriptor/environment integration passed all six executions across both
handoff modes through Make (Gradle: 16 seconds). All 23 consumed artifacts had
matching hashes and graph writers; GHC left no persistent scratch products and
the unchanged Ninja repeat did no work. Eight audits became two, and compiler
root/lambda/count assertions were removed while retaining ABI rejection, context
ownership and first compiled-call checks. The 49 fixture-selection and 98 cache
checks passed, as did manifest ownership validation.

Exception-result fixtures passed all 198 executions across both handoff modes in
1m48s. All 58 declared products existed and 45 consumed artifacts had matching
hashes and graph writers; the repeat did no work. The general native model now
starts once instead of 47 times, retaining all 141 observations. Audits run once
per export stage: three on AArch64, four on x86_64, instead of 34/50. AArch64 keeps
its existing pre-Tidy-only wide-layout export; scalar Core covers both stages.

Original descriptor readiness passed eight executions across both modes through
Make in 14 seconds. All 76 consumed artifacts had matching hashes and graph
writers, the native scratch file was removed, and the repeat did no work. Its
22 audit processes became 11 while requiring rejection evidence for both safe
and unsafe entries. Fixture selection, cache inventory and ownership checks passed.

Address fields passed 16 executions across both modes through Make in 36 seconds,
plus five Python ABI checks. Its eight consumed artifacts had matching hashes and
graph writers, all declared products existed and the repeat did no work. Two
batched audits replace 18 reports. GHC expression-topology and Truffle call-node
inventory assertions are removed; native values, lazy neighbours, typed carrier
rejection and first compiled calls remain.

The separate close fixture is removed: native close/invalid-reuse behavior is
covered by the Unix descriptor lifecycle, with ownership and disposal covered by
the managed-file suites. The five retained descriptor/stdio/ABI classes passed
110 executions with two Linux-only skips across both modes in 15 seconds. Exact
compiled-entry deltas in the retained call test became positive execution checks.
The smaller Haskell tool built successfully; fixture/cache checks passed. The full
selection check exposed one obsolete Unix dependency on ArrayCoreEvidence, which
was removed and its focused ownership check passed.

The duplicate native dup/dup2 pipeline is also removed. ManagedDescriptorDupTest
covers shared offsets, lowest-free allocation, alias replacement, final-close
ownership, errno and callback failures; OriginalStdioCallTest checks first compiled
calls and state rejection, and UnixLibcTest checks real installed declarations.
Their verified results above are reused. This removes 19 native process launches,
eight audits and 167 retained artifacts. The Haskell tool and all 102 selection,
49 fixture-selection and 97 cache checks passed after removal.

Truncation passed both mode executions through Make in 14 seconds. All 65
declared products existed and all 56 consumed artifacts had matching hashes and
graph writers. Deleting one result file rebuilt its producer in 3.80 seconds;
an unrelated file survived and was not consumed. The repeat did no work in
0.45 seconds. One native invocation replaces 14 and two audits replace four.

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

The focused JVM results do not establish that all 123 mapped classes pass.
Linux generation/execution of the newly migrated rules, native Windows graph
support and migration of the CI entry point remain open. Successful local
fixture generation is not a claim that CI is green.
