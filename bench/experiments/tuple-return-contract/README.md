# Tuple return contract experiment

This experiment demonstrates a possible implementation mechanism on pinned Graal/Truffle. It does **not** exercise THC's production tuple lowering and cannot establish that a THC entry has this behavior.

An inlined guest producer creates a fresh StaticShape carrier with typed fields. Its caller consumes the fields while the carrier remains virtual. A standalone guest root publishes into a reusable typed slab and returns a completion token; a forwarding root normalizes any fresh result before crossing the actual root boundary. This experiment uses a deliberately small single-completion slab, not the production pool or its ownership checks.

The result is scalar values in the compiled caller, subject to register allocation and spills. It is not a multiple-register machine-call ABI. Stock Truffle still returns one Object across a residual call; the captured final LIR says `RETURN ... additionalReturns: []`.

## Reproduce

Build `installDist` with this repository's pinned Graal toolchain, then run:

```sh
JAVA_HOME=/path/to/graalvm/Contents/Home \
  bench/experiments/tuple-return-contract/run.sh /tmp/tuple-return-contract
```

`THC_RUNTIME_ROOT=/path/to/built/thc` may select an existing runtime installation. The probe uses that installation only to create a Truffle language/context; it defines its own producer, forwarder and consumer roots. The script compiles the probe and the repository's `tools/GraphInspect.java`, runs inlined and residual controls, dumps actual BGV and backend CFG files, and audits the mixed caller's pre-lowering and low-tier graphs. Large graphs remain in the requested output directory. `evidence.json` and `final-lir-excerpt.txt` are compact, derived evidence. This directory records a completed run in `evidence-graal-25.3.4.1-aarch64.json` and `lir-graal-25.3.4.1-aarch64.txt`.

The recorded run used GraalVM 25.3.4.1, JDK `25.0.4.1+1-LTS-jvmci-25.3-b22`, macOS AArch64, compact object headers, and compiler/Truffle revision `7b025988a922a73286d1326e1eddc1ca39d3f569`. The script records the actual Java version, JDK release metadata and Java source hashes for each run.

## Controls and findings

- `TupleDirectiveProbe`: ordinary Java helpers see scope bits 0 in the interpreter, 3 in a standalone compiled producer, and 2 inside a guest-inlined producer (`inCompiledCode` is bit 1, `inCompilationRoot` bit 0). Correct producer/forward/consumer paths execute and stay compiled. The deliberately unnormalized outer root compiles to an unconditional deoptimization in `OptimizedCallTarget.profileReturnValue`, then invalidates on its first call: the interpreter profiled the completion-token class while the compiled root would return the fresh-carrier class. Nested, correctly inlined producers do not suffer this mismatch. A successful compile request without a subsequent valid compiled execution is insufficient evidence.
- `MixedTupleProbe`: two independently varying full-width longs and an unforced reference cross producer and forwarding roots. The consumer checks arithmetic derived from both longs and compares the reference against a separate dynamic argument. A payload method throws if forced. Positive values, incorrect checksums and distinct reference controls pass both with and without guest inlining. Boolean singleton results avoid confusing host-result boxing with tuple allocation.
- The mixed caller's graph removes all carrier allocations and field traffic. The recorded mixed caller has 50 nodes before high-tier lowering and 66 after low-tier lowering, with no allocations or writes in either phase. In the recorded low-tier graph, the remaining reads are host argument-array elements, boxed Long/Boolean inputs and the Truffle handshake flag; the only invoke is the handshake. The final AArch64 LIR performs long arithmetic and reference comparison in physical registers. Its normal path has no guest call or carrier memory operation. Slow handshake/deoptimization paths still exist and may spill live values.
- The explicit materialization control places a side-effecting `@TruffleBoundary` observer after tuple completion, without passing the carrier to it. A cold request then deoptimizes before consumption. The interpreter counts consumption of a non-token carrier: exactly one for the inlined case, zero for the residual case, with identical values and reference identity. This prevents a pure cold branch being hoisted before tuple creation and falsely presented as evidence of materialization.
- `VirtualEscapeProbe`: a fresh object has the same return class in interpreter and compiled code, so return profiling cannot mask the test. `ensureVirtualized` followed by an escaping return causes the expected hard compilation failure. The directive is an assertion, not a hint or an automatic switch to slab publication.

## Pinned source contracts

The installed JDK's `release` file identifies the exact source revision. The relevant source is:

- [`CompilerDirectives.java`](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/truffle/src/com.oracle.truffle.api/src/com/oracle/truffle/api/CompilerDirectives.java#L145): `inCompilationRoot` distinguishes a non-inlined compiled CallTarget from an inlined CallTarget; interpreter returns false. `ensureVirtualized` applies at all dominated points; `ensureVirtualizedHere` only at its current point.
- [`TruffleGraphBuilderPlugins.java`](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/truffle/substitutions/TruffleGraphBuilderPlugins.java#L383): after partial-evaluation decoding, the directive tests external guest inlining depth equal to zero. Ordinary Java helper nesting does not define this depth.
- [`PEGraphDecoder.java`](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/replacements/PEGraphDecoder.java#L364): computes external inlining depth from enclosing guest-inline root markers.
- [`EnsureVirtualizedNode.java`](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/compiler/src/jdk.graal.compiler/src/jdk/graal/compiler/nodes/virtual/EnsureVirtualizedNode.java#L61): marks virtual objects and raises a graph error when materialization is required in compiled code. Deoptimization frame state can still materialize private carriers for interpreter continuation, as the side-effecting control demonstrates.

Production acceptance additionally needs real AST and bytecode entry graphs, actual execution after compilation, tail forwarding and residual-call checks, and pool ownership checks after cold paths and exceptions. These experiment results must remain separate from that evidence.
