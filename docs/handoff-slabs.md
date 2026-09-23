# Opt-in dense handoff

`-Dthc.handoffSlabs=true` enables dense argument transport for eligible direct
AST calls. It is disabled by default and independent of the default-off
`thc.callDemands` option. The actual Truffle call ABI remains
`Object[] -> Object`; this is a language-level storage protocol, not a native
multiple-register ABI.

Eligible arguments have one supported Long or boxed-reference representation.
Captured environments occupy another reference field. Results use either a
private completion token with an immediately consumed Long register, or the
ordinary reference return. Bytecode, indirect calls, unsupported signatures,
and tail chains without a handoff receiver retain ordinary dispatch. Aggregate
metadata remains rejected, including zero-width and singleton unboxed tuples.

## Ownership and reentrancy

The language owns interned layouts keyed by physical Long/reference fields.
Each context thread owns a pool with at most one active incoming loan and one
reusable storage object per layout. There are no global payload roots.

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

## Integration coverage

This integrates the reference-return v3 implementation from experiment commit
`80ba011b1bd954fc75f5bb090730c61f8e94e0e2` onto main `7dc2281`. The archived
186-test suites on `experiments/handoff-slabs` belong to the earlier frozen
caller-demand baseline; they are not current-main validation.

The current tests cover deep non-tail calls, ancestor tail cycles, mixed Long
and reference returns, lazy PAP prefixes, escaping captures, retained frames,
deoptimization, exceptional cleanup, physical-representation aliases, and
lexical scope forks across non-inlined calls. The full suite also compares
20 real-GHC corpus entries and 318 native-oracle input/result pairs on both
backends, with cold paths and explicit guest recompilation, and checks the
aggregate rejection frontier and caller-demand gate.

Fresh integration runs passed **236/236 tests with handoff disabled and
236/236 with handoff enabled**, with no failures, errors or skips. These runs
used the current generated GHC fixtures, independently of the archived
experiment's test outputs.

After selecting the pinned GHC 9.14.1 and GraalVM 25.3.4.1 toolchains, regenerate
fixtures and run both configurations. `--rerun-tasks` is intentional: changing
an external JVM property must not reuse another configuration's test result.

```sh
scripts/prepare-tests.sh
scripts/gradle.sh --no-daemon test --rerun-tasks
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true scripts/gradle.sh --no-daemon test --rerun-tasks
```

No new benchmarks or rollout decision accompany this integration. The frozen
v3 reference measurements are smoke checks only. The interpreter still stages
an argument packet, and handoff storage can inhibit allocation elimination
when a call inlines.
