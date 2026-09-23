# Exact empty inputs in production graphs

These controls execute genuine pre/post-Tidy GHC exports on the production AST
and bytecode runtimes. They exercise `before (# #) x`,
`between x (# #) y`, and a scalar-only caller. They check correctness and compiled
graph structure; they do not measure execution time.

The captured runtime is `6764d208d8685e2e001a41a0d099ff0a79f723ca`, using GraalVM
25.3.4.1 on AArch64. The native fixture has 118 ordinary rows and seven independent
two-input rows. This graph matrix selects eight `beforeCase` rows, eight
`scalarControl` rows, and all seven `betweenInputs` pairs for each of pre/post
Core, AST/bytecode, and normal/disabled inlining: 24 fresh-JVM controls. Every
control checks all native rows twice after compilation and requires its warmed
active guest target to remain unchanged and valid after each row.

## Observed

All 24 controls pass. In the 12 normal-inlining controls, the selected caller has
no surviving argument packet, tuple-carrier allocation, guest call, or guest
layout/carrier memory traffic. Arithmetic remains `i64` in the compiler graph;
the final AArch64 LIR uses physical `QWORD` registers for the two independent
values. Incoming host `Object[]` arguments, their boxed `Long` fields, the final
host-result `Long` box, and the runtime handshake remain. Deoptimization metadata
can describe virtual frames and packets without materializing them.

Counts below are before high-tier lowering / after low tier. Both Core stages
have the same counts.

| Entry | AST inline | Bytecode inline | AST residual | Bytecode residual |
| --- | ---: | ---: | ---: | ---: |
| `beforeCase` | 26 / 114 | 54 / 143 | 66 / 133 | 84 / 156 |
| `scalarControl` | 22 / 97 | 47 / 120 | 78 / 258 | 83 / 258 |
| `betweenInputs` | 33 / 122 | 62 / 151 | 71 / 140 | 89 / 163 |

Each residual caller has one real `Object[]` packet and one
`OptimizedCallTarget.callBoundary` call. The committed packet has exactly two
elements for the one-input controls, or three for `betweenInputs`: header plus
scalar payloads, with no empty-tuple element. This remains a memory-based Truffle
call boundary. The inline register evidence does not establish a multi-register
machine-call ABI, guarantee that every program inlines, or rule out spills.

`scalarControl` preserves an opaque scalar call with compensating arithmetic
(`scalarBefore (x + 1) - 3`) because GHC otherwise eta-reduces the wrapper to an
alias. Its result equals `beforeCase` (`3*x+7`), but its call is non-tail while
`beforeCase` uses the existing tail protocol. The graph counts are not a timing
or equal-cost comparison.

An earlier capture at `08130ae` exposed bytecode loads of
`GuestRoot.inputLayout`, `ArgumentLayout.empty`, and its boolean mask, including
on the scalar-only control. The owner fixed direct-site proof normalization in
`6764d20`; the final captures check that those reads are absent. Unknown-target
validation remains in the runtime. No compiler size/loop limits were raised.

## Representation and ownership review

The reviewed source distinguishes exact `components: []`, `primReps: []`
unboxed tuples from boxed unit and scalar `State#`. `ArgumentLayout` maps logical
positions to physical payload offsets; `null` retains the scalar-only path.
`Closure.suppliedCount` advances for empty PAP arguments independently of the
physical prefix. Empty formals bind to a tuple alias with no local slot, and
empty operands still execute their destination writer in source order.

Cached direct targets and logical arity determine their formal mask and prefix;
dynamic targets retain runtime checks. Strict input positions are precomputed
physical indices. This avoids a speculative dynamic loop over logical flags.
Optional input handoff filters empty fields before generating the storage class.
Input and tuple-result loans have separate pools; input entry copies durable
locals and releases the input before the body runs. These captures use the
default packet convention (`thc.handoffSlabs=false`); the source review and the
feature's separate handoff/ownership tests cover that optional protocol.

## Reproduce

Set `JAVA_HOME` to the pinned GraalVM installation and `GHC`/`GHC_PKG` to GHC
9.14.1. From the repository root:

```sh
python3 scripts/prepare-empty-tuple-input-audit.py
python3 bench/experiments/empty-tuple-inputs/run.py \
  /private/tmp/thc-empty-input-graphs-new \
  --capture bench/experiments/empty-tuple-inputs/captured
```

The runner requires a new output directory, verifies native provenance and both
strict audits, checks runtime/build files against the recorded source commit,
builds `installDist`, and pins source, tooling, installed JAR, native-input and
JDK hashes before launch. It uses normal inlining, or only `compiler.Inlining=false`
for the residual controls. Background/multi-tier compilation are disabled and
the automatic threshold is raised to keep compilation explicit; graph size and
loop budgets are unchanged. The selected active guest target is compiled last.
Only the two inspected graph phases are expanded from each BGV file.

```sh
python3 bench/experiments/empty-tuple-inputs/run.py \
  /private/tmp/thc-empty-input-graphs-new --collect-only \
  --capture bench/experiments/empty-tuple-inputs/captured
```

Collection verifies case identity, exact native row counts and pass marker,
launch hashes, selected target, allocation/call/memory invariants, raw BGV/CFG
hashes, and equality of the retained final LIR with its CFG block. The repository
retains compact counts/hashes, LIR excerpts, and native rows; raw graph files stay
in the external output directory. Instrumentation is disabled for these graphs,
so validity is not presented as an independent compiled-entry counter. The
feature's instrumented JVM tests separately enforce per-row compiled entry and
host/original/active target checks.
