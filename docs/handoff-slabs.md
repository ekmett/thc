# Opt-in dense handoff

`-Dthc.handoffSlabs=true` enables dense scalar argument transport for eligible
AST direct-call-cache paths. It is disabled by default and independent of the
default-off `thc.callDemands` option. The actual Truffle call ABI remains
`Object[] -> Object`; this is a language-level storage protocol, not a native
multiple-register ABI.

Eligible scalar arguments have one supported Long or boxed-reference
representation; exact [empty tuple parameters](empty-tuple-inputs.md) contribute
no physical fields. Captured environments occupy another reference field.
Results use either a private completion token with an immediately consumed
Long register, or the ordinary boxed-reference return. Float, Double, address,
vector and aggregate results do not select this optional scalar protocol.

Async-enabled AST roots, bytecode roots, generic indirect scalar calls,
unsupported signatures and tail chains without a handoff receiver retain their
other dispatch paths. Sharing `HandoffStorage` or `HandoffLayout` does not make a
path conditional on this flag: [typed tuple inputs](tuple-inputs.md),
[binary sum transport](sum-inputs.md), [vector transport](simd-families.md) and
the [typed result protocol](tuple-results.md) have separate contracts on both
backends. In particular, a singleton-reference tuple is not a scalar reference.

## Ownership and reentrancy

Each context owns interned layouts keyed by physical Long/reference fields.
Its context-thread-local pool has at most one active incoming loan and one
reusable storage object per layout. Separate contexts share no payload roots or
mutable layout registry.

All operands, PAP prefixes and required entry forcing are evaluated or staged
before acquiring a loan. Entry copies the original packet into durable frame
snapshot slots, restores live argument and environment slots, then clears and
releases the loan before executing guest code. Nested calls can therefore reuse
the storage without changing an older activation's arguments. The snapshots
survive materialization and deoptimization; a debugger adapter for them is not
implemented.

A tail transfer owns its loan until the receiving root restores it. Generation
checks prevent unwinding an older call from releasing a reused loan. Copy and
entry failures release reference fields, and ordinary guest failures occur
after entry has already released them. Long results have a separate lifetime:
the caller decodes only the private completion token immediately after return.
An ordinary boxed Long or reference never reads that register.

The interpreter still stages an argument packet before copying it into the
handoff fields. This protocol does not by itself prove allocation elimination
or a performance gain when a call inlines.

## Check the current implementation

With the pinned toolchain, run the focused protocol checks in separate mode
forks sharing one compilation:

```sh
./gradlew --max-workers=2 --continue \
  testDefault --tests 'thc.runtime.HandoffTest' \
  testDense --tests 'thc.runtime.HandoffTest'
```

These tests construct their Core inputs directly. They cover layout/pool
ownership, nested calls, tail cycles, lazy prefixes, captures, retained frames
and exceptional cleanup. The dedicated
`requestedModeReachesTestProcessAndContext` test observes each fork's mode
without changing it; other protocol tests deliberately enable handoff locally
and restore the previous setting. Running those controls in a default fork is
therefore not evidence that every test body leaves handoff disabled.

`testDefault` and `testDense` always execute fresh tests and keep separate
reports. Do not use `--rerun-tasks` to select the mode or force unrelated
compilation. For the full suite with fresh native/Core fixtures, use the
[development workflow](contributing.md#build-and-test); selected synthetic
protocol checks are not a replacement for original-GHC coverage.
