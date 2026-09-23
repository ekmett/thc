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

## Follow-up: the first interpreted root is the host bridge

A separate build-only root-entry overlay reproduces the same bytecode failure.
It stores root references and entry-time `inCompiledCode()` values in a bounded
ring, armed after explicit compilation, and dumps only when the original
assertion fails. No guest invocation, settling call or retry was added.

The 15 recorded events have no overflow. The stable `THC host entry/1` root
enters in interpreter mode. Its selected `lambda raw` target and the actual
guest root entered have identical identities; the actual guest entry is also
interpreted. All twelve following nested guest-root events are interpreted.
At the later failure dump, the host and selected guest still have valid
last-tier code. Entry mode and post-failure validity are distinct observations;
this does not assert that no target state could change between them.

This localizes the first observed bypass to the public host bridge, not merely
a deeper guest function or the wrong split clone. It does not by itself name
the native caller link responsible. The diagnostic preserves the original
failure after 7,610 comparisons and 2,596 earlier required compiled calls.
Its JAR SHA256 is
`39629c8f2e474bb9139c6a707a66debe0576d9ac6f201f94432983e66576808e`,
and its failed checker log is
`adc9ae21106345bad6437f052cd0e353d6c477bb354cb59e0357c55f5781760f`.
The overlay changes generated code and timing; it is a diagnostic reproduction,
not an unmodified coverage pass.
The [compact experiment](../bench/experiments/sequence-entry-attribution/README.md)
retains the exact overlay, failure excerpt, hashes and reproduction commands.

## Controls that do not establish a fix

Two further build-only checker overlays inspect the host, guest and five Java
caller methods immediately before the first Sequence call. One only observes;
the other additionally invokes JVMCI `reprofile()` on the five caller methods.
Both finish with 8,028 comparisons and 2,760 positive required compiled calls.
Both preserve host/guest code addresses and call counts across the diagnostic,
with zero compiled entries before the measured call. Because observation alone
changes the outcome, this pair is **inconclusive** about caller retirement as a
remedy. Neither observation nor re-profiling is proposed for production.

A tiny standalone public-call experiment also fails to reproduce the desired
zero-entry symptom: both first and second calls add one compiled entry. It is
not a deterministic regression for the production failure.

## Boundary lifetime lead and its limits

The pinned AMD64 compiler source emits its successful G1 boundary tail jump
before frame setup and the boundary nmethod's entry barrier. Under ZGC it emits
that jump after the barrier instead. The pinned native binary connects that
barrier to aging: `BarrierSetNMethod::nmethod_entry_barrier` calls
`nmethod::mark_as_maybe_on_stack` at `0x562d8c`; that method writes the current
`CodeCache::gc_epoch()` to nmethod offset `0x48` at `0xe29c11`.
`nmethod::is_cold` reads the same field and compares its age with
`CodeCache::cold_gc_count()` at `0xe2a342` through `0xe2a35a`.
This provides a possible mechanism for an active fast trampoline aging as cold,
but is not a direct sample of the retired boundary's age field or evidence that
it was active at retirement. Loading/auditing rejected frontiers can also leave
the boundary genuinely idle. An entry barrier cannot refresh code that is not
being entered.

An unchanged-checker bytecode replay with only ZGC and lifecycle logging added
passes 8,028 comparisons and 2,760 required compiled calls. Its log shows one
boundary installation and no boundary retirement. This is **not** an isolated
entry-barrier experiment: the collector also changes compressed-oop behavior,
timing, and the recorded `cold_count` (2147483647 versus 32 in the earlier G1
cold-flush record). Compact object headers remain enabled. No default collector
change or default-runtime coverage pass follows from this result.

The controls are retained under `build/link-control-*` in the owner's
`thc-sequence-linkage-control-01a0cdeb` worktree. Their output SHA256 values are:

```text
527208b38d0902b8e8df44ec22bfc16368c1c9063c4aa1bceb896de26a69baa8  observe.log
318eabecd4e3488194122df23c0ff69334a320c049ce23c8db58c49493a95975  reprofile.log
599756cd9dc3151a52e0ccf2a4039a3724147b90c4ee80c63ba24a14d3138d3f  zgc.log
5a2104a2d4c2a8777c749840a18fd0f6bbbbd74309b890c080892d6a0ed621f0  zgc-lifecycle.log
```

The remaining blocker is the pinned public host-entry/VM caller-link path.
Restoring the stub alone does not guarantee first-call compiled guest activity;
no supported public API to repair all preexisting caller links was found.
Further work needs a discriminating caller-link or runtime-lifetime fix while
retaining the ordinary public call and unchanged default-runtime gate. More
primops, Sequence source substitutions, counter relaxation and diagnostic
settling do not address this observed failure.

## Java-Graal lifetime control: inconclusive

A local module overlay moves G1's boundary tail dispatch onto the compiler's
existing post-frame path, after its entry barrier. It leaves the installed JDK,
THC runtime, manifest and checker unchanged. Both that experiment and the
unmodified Java-Graal control pass all 8,028 comparisons and 2,760 required
compiled-entry checks. The experimental run still cold-flushes its boundary at
35.259s and reinstalls it at 91.349s; the control records no boundary retirement.
Thus a passing patched replay is not evidence for a fix. Compiler host,
observation, profiles and timing remain confounders, and genuine idle retirement
would not be prevented by the change.

The [compact lifetime experiment](../bench/experiments/sequence-boundary-lifetime/README.md)
records configuration, compiler-selection evidence, results, artifact hashes and
retained full-log locations. Neither a Java-Graal compiler-host switch nor a GC
change is adopted in production. Default-runtime Sequence coverage remains
blocked at the original strict entry gate.

Later native-compiler [follow-ups](../bench/experiments/sequence-boundary-lifetime/native-followups.md)
also pass under added observation: neither per-call nor constructor-only weak
dispatch tracing reproduces the failure, so neither supplies cache attribution.
An unmodified checker with external output timestamps measures a 47.530-second
frontier-processing interval, but records no boundary retirement. It does not
establish that any earlier retirement happened during genuine idleness. The
preserved failures and the default strict integration gap remain open.
