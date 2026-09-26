# Boxed compare-and-swap

`casArray#`, `casSmallArray#` and `casMutVar#` compare references by identity,
without forcing the expected value, replacement or stored payload. They accept
either known boxed levity. Success returns `(# state, 0#, replacement #)`;
failure returns `(# state, 1#, observed #)`. The failed result is the observation
from the atomic comparison, not a later read and not the stale expected value.
It can be used as the next retry ticket.

Both runtime backends use JVM full-memory-order compare-and-exchange. Array
bounds use the full-width index before narrowing; ordinary arrays, small arrays
and mutable variables remain distinct storage families. These operations do not
compare Haskell values or call Kotlin `equals`, and do not remove the usual ABA
property of pointer CAS. Callers must use the identity protocol rather than
assuming structural equality implies a successful exchange.

Completed THC thunk updates are followed as indirections for CAS identity, so
pattern-matching a ticket does not leave it permanently different from its stored
wrapper. This never enters a thunk: only an already-published WHNF is inspected.
After an indirection match, exchange retries against the exact raw observation;
a concurrent writer cannot be overwritten by an unconditional store. Unevaluated,
owned, failed and suspended thunks remain opaque. Generic pointer equality is
unchanged.

`atomicModifyMutVar_#` atomically installs one shared lazy application `f old`
and returns `(# state, old, result #)`. The returned `result` is exactly the
application installed in the cell. Neither `f` nor `old` is forced by the update;
forcing the application later shares its value, exception or resumable work.
Unlike `atomicModifyMutVar2#`, it does not install a first-field selector.
Contended retries may allocate abandoned application thunks but never evaluate
their modifiers. This is not an allocation-free claim.

## Example and checks

[`THC.BoxedCasCounter`](../examples/THC/BoxedCasCounter.hs) is a small pure
counter example. Its retry loop uses the observation returned by `casMutVar#`.
The unary `boxedCasCounter` entry performs `n .&. 63` increments, including for
negative inputs. For example, `boxedCasCounter 17` returns `17`.

Generate the native/Core inputs with:

```sh
cabal run exe:thc-fixtures -- boxed-cas
./gradlew test --tests thc.runtime.BoxedCasTest --rerun
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --tests thc.runtime.BoxedCasTest --rerun
```

The producer records genuine GHC 9.14.1 pre-/post-Tidy Core, twenty strict entry
audits, 90 native observations and source/artifact hashes. The ten entries cover
both boxed levities, failed-ticket retry, unforced bottom replacements, lazy
modifiers and the example. Kotlin checks independent arithmetic expectations on
AST and bytecode, with and without inlining. Focused runtime controls cover
reference identity, state/bounds failures before mutation, full-memory-order
publication races and shared lazy modifier results/failures. Direct primitive
roots have exact `+1` first-installed-entry checks; the public counter has
data-dependent calls, not a universal constant call-count assertion.

The recursive counter's bytecode call graph without inlining has shown target
retirement after a correct compiled-active invocation. That JIT-retention result
is recorded, not claimed to pass. The example still checks every native value
and compiled activity from the first installed invocation, without settling or
retrying a failed call. Direct primitive roots retain their exact count and
target-validity assertions.
