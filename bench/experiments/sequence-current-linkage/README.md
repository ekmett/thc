# Current Sequence linkage control

The historical first-call host-linkage failure was **not reproduced**, and no
runtime fix or additional Sequence coverage is claimed. Runtime sources remain
unchanged at `81f5c296dd3e819444ab601ca1b4e84cf176c02b`.

One bounded matched pair uses the existing public `Value.execute` path and
default handoff mode, with the historical `-Xmx6g` bound. Both AST and bytecode
pass 7,613 native comparisons and 2,599 mandatory positive compiled-entry checks.
The first measured `sequenceBuildViews(1)` call follows exactly 7,610 comparisons
and 2,596 required compiled checks, matching the historical failure's position.
Its native result is 258103; the next two warm results are 5435681624 and
5420159424. No Sequence call is inserted between explicit compilation and the
first measurement. An earlier AST control using ergonomic heap sizing also
passes; it is recorded separately, not counted as a matched pair member.

This does not promote the views into trusted coverage. The historical
[`ffdbca6f` investigation](https://github.com/ekmett/thc/blob/ffdbca6f091b6ee48cd9baed2a8f61e3a6eb5a3d/docs/sequence-entry-boundary.md)
recorded a native-correct result with zero compiled entries, and attributed the
first interpreted root to the public host bridge. Passing timing-sensitive
controls do not erase that observation.

## Exact scope and differences

`checker.patch` is a build-only overlay of the current `LibraryCheck.kt`. Its
original scalar-library `checkRows` call site and counter assertion handle both
the existing prefix and the selected view. The existing prefix retains its
ordinary cold-training and second compilation. The Sequence view has one
compilation, three immediate warm measurements, and no settling, retry,
post-cold replay or recovery compilation.

The seven historical large Sequence load positions are retained, without guest
execution. Build, Ends, Append and LazyPayloads now load because exact-empty
unboxed tuple inputs are supported. Split still rejects its unsupported tuple
constructor path. IndexUpdate and Aggregate still reject the missing
`GHC.Internal.Show.$fShowCallStack_itos'` binding. These are exact expected load
outcomes, not numerical passes for those entries. BuildViews then runs its 38
native rows in a separate interpreted context, followed by a fresh context,
40 normal warmup calls, explicit compilation, and the unchanged first-call gate.

The archived Sequence and boot modules are original bytes from a genuine
GHC 9.14.1 / containers 0.8 post-Tidy export. They are equivalent inputs, not the
exact historical CI artifact. The archive does not include the old unrelated
five-group Core trees or audits. Those come from the current prepared library
fixtures; their native row prefix is byte-identical to the archived oracle.
Two preparation-source fingerprints whose checkout had since moved were
recovered from exact matching Git blobs. No stale fingerprint was relabelled.

The append-only JSON writer and expanded runtime are additional differences from
the historical source. Reduced allocation can affect the boundary lifetime;
these controls do not isolate it as the cause of a passing run. The lifetime
checker retains the original aggregate guest counter gate. It does not add host
root instrumentation, prove the exact native caller route, or independently
establish which compiled root contributed each increment.

Before this pair, isolated controls passed all three additional views on both
backends with handoff off/on, requiring observed host-to-original target links,
target identity, installed validity and native results after each measured call.
A fresh public JSON-loader AST control also passed. Those controls did not
preserve the full preceding library lifetime and are not coverage claims.

## Source diagnosis

The `EntryValue.compile` boundary-repair implementation is unchanged from
`ffdbca6f`; the `Language.kt` difference is unrelated host-formal validation.
Inspection of the installed Truffle runtime bytecode confirms that
`OptimizedCallTarget.interpreterCall` marks a valid-on-entry bypass, calls the
repair hook, and on non-AOT HotSpot does not retry the compiled path for that
invocation. `callBoundary` consequently reaches `profiledPERoot`. Installed
target validity and shared stub restoration do not establish the caller's entry
route.

Installed AArch64 compiler bytecode also retains the conditional lifetime lead:
the backend calls the entry decorator with `true` before the code body; the
boundary decorator emits its G1 jump on that path, before frame entry. ZGC and
Shenandoah choose the later path. This is bytecode/control-flow evidence, not a
live observation of a retired boundary or proof that it caused a current miss.
No architecture-based elimination of the condition is established.

The next discriminating step remains a current-runtime reproduction under the
original failing caller/lifetime conditions, followed by failure-only attribution
if it fails. No runtime workaround follows from this bounded non-reproduction.

## Reproduce

Use the runtime revision above, its pinned GraalVM and Kotlin dependencies, and
an `installDist` build. The supplied Sequence input archive has SHA-256
`2cc2757b7d912520950a5de4f9bfeb05f3230dadddd343d79e30a35ecd10484a`.
Its source/provenance originates at the published `ffdbca6f` branch; the archive
contains all eleven Sequence/boot modules and the full native oracle, but not a
portable complete historical library suite. Extract it without changing Core
bytes. Prepare the current five-group library fixtures normally, or retain the
hash-verified fixtures used by this capture.

```sh
python3 bench/experiments/sequence-current-linkage/prepare.py \
  /path/to/current/build/libraries/cases.json /path/to/sequence-core-inputs \
  build/sequence-host-linkage
python3 bench/experiments/sequence-current-linkage/compile.py \
  build/sequence-host-linkage

# Run once per backend in a fresh process. Keep JAVA_HOME and
# THC_GRADLE_USER_HOME set to the pinned toolchain and its trusted cache.
"$JAVA_HOME/bin/java" --add-modules=jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED -Xss2m -Xmx6g \
  -XX:+UseCompactObjectHeaders -Dthc.handoffSlabs=false \
  -cp 'build/sequence-host-linkage/lifetime-classes:build/install/thc/lib/*' \
  thc.LibraryCheckKt build/sequence-host-linkage/lifetime-cases.json ast
# Repeat the same command with bytecode as its final argument.
```

The preparer verifies original module and oracle hashes and the complete prefix
manifest, recovers only exact historical audit/capability source blobs if needed,
and applies the patch to a build-directory copy. It fails on other drift.
The checked-in preparation and compilation scripts reproduced the captured
checker source and class bytes exactly; this verification did not execute
another guest replay. The normal compiler limits remain 30 seconds and 100,000
graph nodes, single tier and synchronous compilation. No flags alter splitting,
GC, code retirement or installed-code invocation.

`evidence.json` records source, toolchain, JAR, class, input, raw-log and local
bytecode-inspection hashes. Compact excerpts retain the seven loads and immediate
warm outcomes; the complete logs remain at the recorded local paths. The final
Sequence counters (AST 444, bytecode 311) are diagnostic totals, not an assertion
of one root entry per row or a performance comparison. Production files and
coverage declarations are unchanged.
