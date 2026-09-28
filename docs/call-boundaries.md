# Residual call boundaries

Truffle 25.3.4.1 uses an object-array argument and reference-result call ABI.
Typed locals and bytecode operations can preserve primitives inside a compiled
region; a residual call still crosses `Object[] → Object`.

## The pinned ABI

The public call interfaces include:

```java
Object CallTarget.call(Object... arguments);
Object DirectCallNode.call(Object... arguments);
Object IndirectCallNode.call(CallTarget target, Object... arguments);
Object RootNode.execute(VirtualFrame frame);
```

Inlining can merge a callee into the compilation region so allocations disappear.
Argument and result profiles refine types without changing those method
signatures. THC's [Java call bridge](../src/main/java/thc/runtime/Calls.java)
passes the already-constructed packet directly, avoiding Kotlin's defensive
spread-argument copy; it does not introduce a primitive call ABI.

## Typed transport and packet lifetime

An immutable typed carrier can place several primitive values in one object
inside the outer argument array. It does not remove that outer ABI.
Tuple, sum and vector inputs and results use their own
[typed layout protocols](aggregate-layout.md). The default-off
[dense scalar handoff](handoff-slabs.md) supplies reusable storage and entry
snapshots for eligible AST direct calls, independently of those aggregate paths.

A caller-local outgoing array is not automatically reusable. Truffle frames can
retain their arguments, including after materialization, so a later overwrite
could alter a retained earlier frame. A conditional argument-profile copy is
not a lifetime guarantee, and clearing slots after return does not create one.
Reusable transport therefore needs explicit ownership, snapshot and release
rules. Captures and partial applications must own retained values rather than
borrow an active transport loan.

## Optional leading-case return

`-Dthc.leadingCaseReturn=true` enables a bounded compiled direct-call shortcut;
it defaults to off. The accepted callee has a lowered leading constructor case,
a Long result, no captures or typed aggregate/vector input layout, and a nullary
alternative returning a distinct Long formal. No other live formal may be used
by the body. It is not a general partial-inlining transformation or a Map builtin.

All entry obligations, including strict PAP prefixes and unused strict formals,
run before the shortcut. A compiled call with the exact matching constructor
layout returns the existing Long reference without entering the callee.
Interpreted calls and nonmatching values use ordinary dispatch. The shortcut
retains callee source attribution but does not invent a callee frame or root-entry
counter increment.

Async-enabled AST roots do not install this shortcut. Async-enabled bytecode
roots can install it only when there are no strict entry marks. Other unsupported
shapes keep the normal call path.

[`LeadingCaseReturnTest`](../src/test/java/thc/runtime/LeadingCaseReturnTest.java)
covers enabled/disabled and interpreted/compiled paths, entry forcing, PAPs,
source locations, layout rejection and cloned targets.
This is an implemented opt-in mechanism, not a demonstrated throughput win or
a promise that every argument packet disappears.

The [performance questions](../research/open-questions.md#residual-calls-and-allocation) distinguish
remaining ABI costs from avoidable allocations and inlining-policy decisions.
