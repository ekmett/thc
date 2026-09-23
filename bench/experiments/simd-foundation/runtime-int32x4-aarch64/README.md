# Actual Core Int32X4 SIMD on AArch64

These four production controls execute genuine GHC 9.14.1 pre-Tidy Core through
THC's AST and Bytecode DSL backends. Each entry consumes four runtime scalar
inputs. Expected results come from the independent x86 native oracle; no model
rows are relabeled as native results.

Runtime and harness sources are revision
`1b75f278756e8352bd5854d75934e4005e6374d2`. Capture checkout
`e9d59f6b6e02429b006fa51ec8ff7398f43feff2` adds only the native evidence imported
from upstream `596bfe4` and `9b0dfcf`. Those imports do not change runtime,
fixture, or harness sources. The installed runtime was built from the recorded
sources with pinned GraalVM 25.3.4.1/JDK 25 and compact object headers enabled.
`runtime-snapshot.json` was checked both before and after capture; sources and
runtime jars stayed unchanged.

```sh
GHC=<ghc-9.14.1> GHC_PKG=<ghc-pkg-9.14.1> \
  python3 scripts/prepare-simd-audit.py --vector int32x4 --export-only
JAVA_HOME=<pinned-graalvm> ./gradlew --offline installDist
JAVA_HOME=<pinned-graalvm> \
  bench/experiments/simd-foundation/run-runtime.sh build/simd-int32-native-graphs int32x4
```

The source-matched [native oracle](../evidence-int32x4-x86_64/native/README.md)
was generated on Linux x86-64 from fixture revision `090035f`. All source hashes,
the retained native artifact hashes, and all 243 native rows were independently
rechecked before capture. The local Core export is pre-Tidy only: AArch64 GHC's
native SIMD path requires LLVM. This record makes no local native or post-Tidy
claim. `input-provenance.json` retains both origins separately.

| Entry | Backend | Native input rows | Nodes before HighTierLowering |
|---|---|---:|---:|
| vectorCase | AST | 81 | 66 |
| subtractCase | AST | 81 | 75 |
| vectorCase | Bytecode | 81 | 91 |
| subtractCase | Bytecode | 81 | 100 |

Every control matches all 81 native rows in each of two postcompile passes and
keeps the exact entry installed. Final LIR contains physical `V128_DWORD`
`ADD`/`SUB` instructions (and packed `NEG` for the subtraction fixture). The
pre-lowering graphs contain no new-array/new-instance/commit-allocation nodes,
field loads/stores, or residual invokes. Each retains one outer scalar `Long`
result box required by the public `Object` return. Bytecode frame-state virtual
objects are deoptimization metadata, not surviving heap allocations.

These graph runs deliberately disable guest instrumentation. Installed-target
validity is separate from the per-row compiled-entry counter checks in
`SimdInt32VectorTest`, which passed on both backends with handoff off and on.
The graph harness uses ordinary inlining and default graph/time budgets. It does
not force a vector calling convention or promise no register spills. Lane
extraction/reinsertion remains between some arithmetic operations; the evidence
establishes packed execution and allocation elimination, not minimal movement
or a throughput advantage.

The JSON records preserve input, source, JDK and runtime-jar hashes. Logs are
copied unchanged. `final-lir.txt` is the final register-assigned section with
trailing whitespace removed, as recorded by the audit. Artifact paths in JSON
refer to the original isolated checkout and build directory.
