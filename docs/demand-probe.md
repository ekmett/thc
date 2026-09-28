# Caller-demand evaluation

THC can use GHC's structured demand information to evaluate selected operands
when their application executes. This is separate from a callee's
[entry contract](entry-contracts.md) and from proof that a value is already in
weak head normal form.

Caller-demand lowering is opt-in with `-Dthc.callDemands=true`; it defaults to
false. Each AST or bytecode program reads the option at construction. Enabling
it later does not change a program that has already been lowered. Exported
certificates are retained and validated even while the optimization is disabled.

## Certificate and evaluation boundary

The exporter derives `callDemand` from the original typed application using
GHC 9.14.1's `splitDmdSig` and `isStrUsedDmd` APIs. Its `arity` is the demand
signature's saturation threshold, independent of the callee's runtime arity or
number of retained lambdas. `strictArgs` aligns with retained value/coercion
arguments after type arguments are erased.

Only a direct variable head receives a certificate. Head casts/ticks and rewritten
wired subtrees remain conservative. Calls below the signature's arity keep every
mark false. Coercion positions, overapplication suffixes, absent demands and
arguments protected by `lazy` do not acquire forcing permission. The runtime
checks certificate shape and independently preserves the saturation threshold.
Missing certificates keep ordinary argument evaluation; malformed ones fail
loading even with the optimization disabled.

When enabled, a marked operand uses the existing strict argument path instead
of receiving a fresh argument thunk. The permission is confined to execution
of that application. It does not force an enclosing lazy binding or constructor
field, strengthen `entryStrict`, claim that a lifted formal was already evaluated,
or narrow a polymorphic representation. Creating a PAP remains lazy when its
demand threshold has not been reached.

The [compiler metadata reference](../docs/compiler.md) gives the exact schema
and launcher options. Pass JVM properties to the application JVM, not only to
Gradle. The option is independent of constructor strictness, safe-speculation
certificates and entry obligations.

## Validation and experiments

`bin/prepare-tests.sh` exports the genuine demand fixtures and runs
`python3 bin/check-demand-metadata.py --ghc-api`. The audit covers signature
versus Id arity, type/coercion slots, absent and bottom demands, lazy wrappers,
head casts/ticks and metadata-only IO cases.

[`RealCoreCallDemandTest`](../src/test/java/thc/RealCoreCallDemandTest.java)
checks genuine ordinary, polymorphic, lazy and partial applications before and
after requested guest compilation on both backends.
[`CallDemandTest`](../src/test/java/thc/runtime/CallDemandTest.java) separately
checks opt-in behavior, strict failures, PAP sharing, unknown calls and unchanged
formal contracts.

Fewer argument thunks do not establish a throughput improvement: changing an
operand's execution path can also change inlining and compiled graph shape.
Measure those outcomes independently. Typed transport across residual calls
has its own [ownership and ABI contract](handoff-slabs.md), independent of
caller-demand lowering.
