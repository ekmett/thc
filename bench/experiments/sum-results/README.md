# Production unboxed-sum result graphs

This is a fixed-input correctness and generated-code check of the actual THC
runtime at `5c7f620ed77129a4717f99cd60fa130926248a42`, not a timing experiment or
standalone carrier model. `SumResultAudit.hs` is compiled by GHC 9.14.1 before
Tidy. Its independent native oracle supplies seven input pairs for `pairedInputs`
and ten inputs for `lazyLeafCase`. Opaque Haskell producer boundaries survive in
Core; Truffle inlining uses normal compiler limits.

Eight controls cover both backends and normal/disabled inlining. Each warms the
fixed native rows, discovers the actual host call target, compiles once, and runs
two unchanged postcompile passes. The active guest target must stay identical and
valid after every row; residual callees are compiled from observed adopted call
nodes, including BytecodeDSL operation caches. There are 68 native comparisons
per postcompile pass, 136 total. Instrumentation is off in these graphs. Separate
JVM tests require exact compiled-entry counters, original/active/host validity
and result-loan cleanup at both Core export stages, with both handoff settings.

| Backend | Entry | Mode | Before HighTierLowering | After low tier |
|---|---|---|---:|---:|
| AST | pairedInputs | inline | 37 | 182 |
| AST | lazyLeafCase | inline | 90 | 140 |
| Bytecode | pairedInputs | inline | 64 | 205 |
| Bytecode | lazyLeafCase | inline | 96 | 140 |
| AST | pairedInputs | residual | 161 | 398 |
| AST | lazyLeafCase | residual | 157 | 320 |
| Bytecode | pairedInputs | residual | 170 | 401 |
| Bytecode | lazyLeafCase | residual | 172 | 337 |

All four inline controls have no sum carrier allocation, tag/payload field traffic
or guest calls. Scalar Long Object-return boxing remains: the pair consumer has
two distinct return branches and two corresponding allocating box nodes, while
the lazy consumer has one. Every surviving pre-lowering allocating box has exact
`java.lang.Long` type and is used directly as a return value. VM handshake calls
may occur on return paths after lowering. The unselected lifted bottom is never
forced. Final physical LIR retains input-dependent machine-word arithmetic;
`RETURN` uses one Object result with `additionalReturns: []`. This proves scalar
replacement in these inlined roots, not a new multiple-register residual ABI or
a guarantee against register spills.

Residual controls retain real `OptimizedCallTarget.callBoundary` calls and the
typed `handoff__*` result fields. The existing scalar input Object[] packet and
input/output Long boxing also remain. Those packets are not sum payload encoding;
sums use the separately owned typed result slab protocol. No throughput or
cross-platform performance comparison is claimed.

`captured/evidence.json` records all runtime source hashes, source revision, JARs,
JDK release, harnesses, native preparation manifest, selected raw BGV/CFG hashes,
node summaries and exact final LIR. The native rows are copied alongside it. Raw
graphs and run logs are retained at `/private/tmp/thc-sum-production-graphs-reviewed`;
the compact capture does not embed those larger files. Collection validates exact
entry/backend/mode/native-row labels, input hashes, both raw graph hashes, and
final LIR equality against the CFG. Previous exploratory captures are not included.

Reproduce from this branch with pinned GraalVM 25.3.4.1 / JDK 25 and GHC 9.14.1:

```sh
export JAVA_HOME=/path/to/pinned/graalvm
export GHC=/path/to/ghc-9.14.1
export GHC_PKG=/path/to/ghc-pkg-9.14.1
python3 scripts/prepare-sum-result-audit.py
python3 bench/experiments/sum-results/run.py /absolute/new/output \
  --capture /absolute/new/compact-evidence
```

The runner rebuilds `installDist` from committed runtime sources, verifies native
provenance, compiles the graph probe, captures real compiler graphs and audits
them. It parses only the selected high/low phases while retaining full raw BGV/CFG.
Use `--collect-only` with an existing output to recheck a capture without rerunning
compiled guest calls. `GRADLE_USER_HOME` may point at a trusted dependency cache.
