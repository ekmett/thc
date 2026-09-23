# Sequence entry-boundary investigation

The default AST and bytecode library replays remain blocked at the first
compiled-warm `sequenceBuildViews(1)` call. Its result is correct, but the
mandatory guest compiled-entry counter does not increase. Do not treat the
boundary-stub repair as a completed Sequence feature or relax that assertion.

## Tested checkpoint

Runtime `3e5ee6d3506b6ebbb19322eb91df6eed5c250952` restores the pinned HotSpot
runtime's shared entry stub after explicit compilation. Its installed JAR SHA256
is `ce0b5621f91f91461cabb371a386123ce54fb2f4bc939241398cbc7c4b2f46de`.
The test-only follow-up is `9582dadce43bb9ed0d6015831d4972a4ee167c51`.
The immutable coverage/native artifact source is `8a91433`.

Default and handoff JVM suites each pass 312 tests. The unmodified AST and
bytecode checkers each fail after 7,610 successful comparisons and 2,596 earlier
required compiled calls. AST handoff passes all 8,028 comparisons and 2,760
required compiled calls. No settling, retries, disabled splitting, increased
limits or alternative execution API are used.

The isolated synthetic regression proves a narrower fact: retiring the boundary
stub while keeping guest and host code valid leaves the old `compile` operation
without a restored stub. The repair restores it before the next public call,
without changing guest/host call counts or compiled-entry counts. Both old tiny
test calls already entered compiled guest code; this is not a deterministic
reproduction of the production zero-counter failure.

## Two distinct runtime mechanisms

An earlier, independently lifecycle-logged run directly recorded HotSpot
flushing `callBoundary` as cold, with 117239 KB free in the relevant code-cache
heap. That run ultimately passed; it establishes that stub retirement occurs,
not a complete failing-call timeline. See [library coverage](library-coverage.md).

Restoring that stub is not the same as restoring every existing compiled caller
link. The pinned x86 JVM's compiled-to-interpreter adapter calls
`SharedRuntime::fixup_callers_callsite`, then still jumps to the method's
interpreter entry for the current invocation. The fixup cleans the old direct
call site for future linking; it does not execute the new compiled callee.
JVMCI `hasCompiledCode()` reads the method's code pointer and says nothing about
those caller links. No supported public Truffle API for eagerly repairing every
such link has been identified.

This control flow was checked in the pinned `libjvm.so`, not inferred solely
from a Java method name. Relevant symbols are `generate_i2c2i_adapters`,
`SharedRuntime::fixup_callers_callsite`, and `CompiledDirectCall::set_to_clean`.
The binary SHA256 is
`4d61a40cebc89f0d7dcfb276d7567395e5c158e3d73da858e1350c28afb6e743`.
It is Oracle GraalVM 25.3.4.1+1.1 / Java 25.0.4.1 on Linux x86-64.

## Failing candidate with caller-link tracing

A frozen copy of the repaired runtime was run through the unchanged production
bytecode checker, adding only compilation and inline-cache logging. It again
failed the same first required Sequence entry. This run records:

- At 90.631s, the stable host EntryRoot receives installed code.
- At 90.646s, the repaired `callBoundary` stub is installed as nmethod 6086.
- At 90.647s, previously interpreter-linked calls inside
  `OptimizedRuntimeSupport.callProfiled` and `OptimizedDirectCallNode.call` are
  cleaned on the main thread. An `interpreterCall` uncommon trap follows the
  installed-stub event, not precedes it.
- At 90.653s, a previously interpreter-linked boundary call inside
  `thc.runtime.DispatchNodeGen.execute` is cleaned.
- At 90.659s, three such sites in `BytecodeRootGen.continueAt` are cleaned and
  relinked to the new compiled stub. The measured guest-entry delta is still zero.

For example, direct-call site `0x00007d89c22d90f3` was linked to the boundary's
interpreter entry at 89.647s and cleaned at 90.653s. Its enclosing nmethod was
5938, `DispatchNodeGen.execute`, installed at 82.296s. Another site,
`0x00007d89c24b4fb7`, was interpreter-linked at 89.647s, then cleaned and linked
to the compiled boundary at 90.659s. It belonged to nmethod 5831,
`BytecodeRootGen.continueAt`, installed at 82.470s. Code-cache addresses were
reused earlier in the run: attribution uses the latest preceding matching
nmethod installation, not address alone.

These are observed stale-link repairs on the relevant runtime paths. Combined
with the pinned adapter's control flow, they support lazy caller-link repair as
the remaining mechanism; they are not an instruction-by-instruction trace of
every target transition or proof that no other runtime effect participates.

A separate failure-only probe observes a level-4 boundary and unchanged valid
guest/host targets after the miss. One same-checker-site diagnostic call and one
separate cold call then each add seven compiled entries without recompilation.
The original assertion still terminates the probe. Its post-miss sample alone
does not prove pre-miss availability; the installation timeline above supplies
that separate evidence.

## Reproduction and retained evidence

Use the ordinary pinned environment and freshly prepared libraries at the
checkpoint. Run the normal checker with its usual heap/stack/native-access
flags, adding:

```text
-XX:+UnlockDiagnosticVMOptions
-XX:+LogCompilation
-XX:LogFile=build/root-repair-inlinecache/hotspot.xml
-Xlog:inlinecache=trace:file=build/root-repair-inlinecache/inlinecache.log:uptime,tid,tags:filecount=0
```

The full logs are retained under `build/root-repair-inlinecache/` in the
integration owner's `thc-sequence-current-main-01a0cdeb` worktree; the checker
used its separately frozen `build/root-boundary-repair-validation/lib/` JARs.
Diagnostic logging can perturb timing, so a different passing replay does not
erase the preserved failure. These traces are not performance measurements.

```text
96829b897fe0b5e64ebcfee3f764a525a259954e6521a0c64ea3f1c43fc17e92  hotspot.xml
15ec96aff7a367e2548d0dd209474ffa2cae6cd95227842cc9b406e199c31dd8  inlinecache.log
ee89d095b2a4bc4197eca2b727911b156148a7dc359510fd7937026cac9f40e7  check-bytecode.log
```

The same checker-output hash was produced by the uninstrumented candidate run
and the compilation-log-only candidate run. The remaining blocker is actual
public compiled entry, not numeric correctness or missing source definitions.
