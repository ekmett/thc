# THC documentation

THC runs exported GHC Core on Truffle/Graal. Start with a user guide, then use
the representation and runtime references for the behavior your program relies on.

## Build, run and embed

| Task | Guide |
| --- | --- |
| Build THC and run a Cabal target | [Getting started](../README.md), [Cabal integration](cabal.md), [driver options](driver.md) |
| Prepare installed library dependencies | [Complete GHC Core](ghc-core.md) |
| Call native libraries or expose Haskell functions | [Foreign code](interface-foreign.md), [JVM embedding](site/embedding.md), [JavaScript and polyglot calls](polyglot.md) |
| Use context permissions, tracing or JVM observations | [Runtime services](runtime-services.md), [CPU affinity API](cpu-affinity-api.md) |
| Build on Windows | [Windows](windows.md) |
| Package pure code as a native executable | [Native Image](native-image-feasibility.md), [compiled code cache](native-code-cache.md) |
| Investigate a rejected program | [Core audit](../bin/core-audit.md), [primop behavior](primop-behavior.md), [debug locations](debug-locations.md) |

[Library examples](library-coverage.md) include containers, formatting, arrays
and mutable references. [Core compatibility checks](coverage.md) explain how to
run the native-GHC comparison corpus. The generated [primop checklist](primops.md)
is an implementation inventory, not a guarantee that every package closure runs.

## Evaluation and runtime behavior

| Area | References |
| --- | --- |
| Lazy evaluation | [Thunks and updates](thunk-updates.md), [entry demands](entry-contracts.md), [boxed values](boxed-values.md) |
| Functions | [Typed calls and tail cycles](typed-tail.md), [call boundaries](call-boundaries.md), [handoff ownership](handoff-slabs.md) |
| Concurrency | [Async exceptions](async-exceptions.md), [MVars](managed-mvars.md), [STM](stm.md), [thread hosting and scheduling](thread-scheduling.md), [thread status](thread-status.md), [thread inventory](thread-inventory.md) |
| Continuations | [Delimited continuations](delimited-continuations.md), [capture contract](async-continuation-contract.md) |
| Memory ownership | [Byte arrays](bytearrays.md), [pinning and lifetime](pinned-memory.md), [managed addresses](native-addresses.md), [weak finalization](weak-explicit.md), [C finalizers](c-finalizers.md), [compact regions](compact-regions.md) |
| Files and processes | [Native file setup](native-file-provider.md), [stdio](original-stdio.md), [readiness waits](managed-fd-waits.md), [process lifecycle](process-lifecycle.md), [signals](process-signals.md) |
| Observation | [Closure inspection](closure-inspection.md), [stack snapshots](managed-stack-snapshots.md), [GC/statistics/time](gc-stats-clock.md), [hints and tracing](hints-and-tracing.md) |

## Values and primitive operations

| Area | References |
| --- | --- |
| Layout | [Core representation evidence](core-evidence.md), [aggregate layouts](aggregate-layout.md), [constructor fields](aggregate-heap-fields.md) |
| Tuples | [Results](tuple-results.md), [inputs](tuple-inputs.md), [captures](tuple-captures.md), [local joins](tuple-joins.md), [empty inputs](empty-tuple-inputs.md) |
| Sums | [Results](sum-results.md), [inputs and captures](sum-inputs.md) |
| Integers | [Narrow carriers](narrow-integer-carriers.md), [unsigned operations](integer-primops.md), [signed operations](signed-narrow-primops.md), [64-bit operations](explicit64-primops.md), [bits](bit-primops.md), [tuple arithmetic](tuple-arithmetic.md) |
| Floating point | [Scalar operations](floating-primitives.md), [floating vector min/max](floating-vector-minmax.md) |
| Arrays | [Boxed slices](array-slices.md), [small arrays](small-arrays.md), [mutable bytes](mutable-bytearray-ops.md), [resize](resize-bytearrays.md), [atomic integers](atomic-int-arrays.md) |
| Scalar memory | [Aligned](aligned-scalar-memory.md), [unaligned](unaligned-scalar-memory.md), [address atomics](atomic-address.md), [array/address copies](address-array-copy.md) |
| SIMD | [Representation](simd.md), [operation families](simd-families.md), [address memory](simd-address-families.md), [128-bit array memory](simd128-array-memory.md), [wide array memory](simd-wide-array-memory.md) |

## Compiler and contributor references

[Architecture](architecture.md) connects acquisition, linking and execution.
[Compiler/export format](compiler.md), [package manifests](core-package-manifest.md)
and [compact Core](compact-core-format.md) describe current tool contracts.
[Bytecode lowering](bytecode.md), [GHC bytecode objects](ghc-bco.md),
[reusable AST code](reusable-code.md), and [AST case boundaries](ast-case-boundaries.md)
describe the execution mechanisms and their limits.

For changes, start with [contributing](contributing.md), [fast CI](fast-ci.md),
[documentation builds](documentation.md), or [graph inspection](graph-inspection.md).
