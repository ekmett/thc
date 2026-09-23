# Real containers coverage beyond Map

The library workload sources use ordinary public containers APIs. They are
compiled from the same unmodified, SHA256-verified containers-0.8 source archive
as the Map example. No library operation is a JVM builtin.

## Data.Set Int: explicit unsupported frontier

`THC.SetWorkload.setAggregate` builds mixed-sign sets with duplicate insertions,
deletes present and absent keys, combines sets with union/intersection/difference,
queries membership, and folds in ascending order with an order-sensitive checksum.
Empty and negative workloads are defined. Native GHC 9.14.1 agreed with an
independent Python set model for all 1,041 inputs from -16 through 1024.

This is **not currently an executable THC coverage claim**. Its strict reachable
audit has 71 reachable bindings, 17 capability issues and three missing
boot-library definitions. The remaining gaps are in cold exception/state paths:

- `ghc-internal` exception/backtrace workers retain unsupported state-token
  tuple components and unresolved constructor/field representation metadata.
- These paths also reach `readMutVar#` and three missing exception-construction,
  backtrace-mechanism and frozen-call-stack definitions.

The ordinary unboxed pair/triple results in deletion, union, difference and
intersection are no longer the capability blocker: result tuples and tuple-result
joins are supported. The current audit has no `aggregate-boundary` issues and
no missing `main:` source-library body. The preparation helper checks the exact
remaining issue owners, details and multiplicities, the three missing identities,
and retained pointer identity; an unrelated rejection cannot substitute for this
frontier.

Insertion, deletion, union and intersection retain the real
`reallyUnsafePtrEquality#` primitive. It is now supported on both backends as
non-strict reference identity, without following thunk indirections. Separate
native-oracle fixtures test identity shortcuts with valid value-based fallbacks;
they do not require GHC and THC to make identical allocation choices. This does
not make the Set workload executable while its exception/state gaps remain.

The initial post-Tidy source export had 71 reachable bindings, three missing
boot-library definitions and 210 capability issues, including 101 explicit
aggregate-representation diagnostics. The original pre-Tidy export also missed
the actual identity of `main:Data.Set.Internal.merge_$smerge1`; the post-Tidy
boundary resolves it without aliases. Counts can change as the exporter learns
more precise diagnostics; rejection does not count as execution support.
The [focused source-binding audit](set-source-binding-audit.md) reproduces the
before/after identity difference and confirms that the unsupported issues remain.

The full source and its strict diagnostic retain the genuine tuple and pointer
operations alongside the remaining exception/state frontier. No synthetic boxed
tuples or ad hoc name substitutions are used to make this frontier appear supported.

## IntMap and word primitives

`THC.IntMapWorkload.intMapAggregate` uses `Data.IntMap.Strict` insertion with
combining, adjustment, deletion, membership, lookup, size and an order-sensitive
fold. Its signed keys include `minBound`, `maxBound`, negative keys and zero;
the workload includes duplicate, absent and empty-map operations.

The original Set/IntMap entries in the dedicated native driver and independent
Python models agree on 994 entry/input pairs: 22 Set inputs, 22 IntMap inputs and 190 inputs
for each of five word-primitive entries. The primitive fixtures cover `clz#`
and unsigned `ltWord#`, including zero, all 64 bit positions, adjacent values,
the sign boundary and the all-ones word.

The initial pre-Tidy IntMap audit found five missing worker identities:
`Data.IntMap.Internal.$wdelete`, `Data.IntMap.Internal.$wgo`,
`Data.IntMap.Strict.Internal.$winsert`,
`Data.IntMap.Strict.Internal.adjustWithKey_$sadjustWithKey`, and
`Data.IntMap.Strict.Internal.insertWithKey_$sinsertWithKey` (all in unit `main`).
These definitions exist in the source export under pre-Tidy private identities,
while consumers refer to their actual Tidy-generated interface identities.

The library preparation helper selects the existing, explicitly recorded
post-Tidy/pre-CorePrep exporter for the complete Set and IntMap compilations.
This uses GHC's actual definitions and names, not a name-matching heuristic.
The primitive fixture still uses the ordinary pre-Tidy boundary. IntMap's
regenerated strict audit accepts 26 reachable definitions with zero missing
globals or capability issues. The helper requires `clz#` and `ltWord#` to remain
reachable in both the genuine IntMap workload and primitive fixture.

## IntSet and bitmap primitives

`THC.IntSetWorkload.intSetAggregate` exercises the public `Data.IntSet` insertion,
deletion, membership, size, union, intersection, difference and ascending-list
APIs. The workload combines dense bitmap leaves with mixed-sign Patricia
prefixes, explicit `minBound`/`maxBound` keys, values on both sides of 64-bit
leaf boundaries, duplicates, absent deletions and empty/negative inputs.
An order-sensitive checksum distinguishes signed ascending traversal from
unsigned or reversed traversal. Expected results come from the same native GHC
driver and a separate Python set model, not from reproducing the Patricia tree.

The three additional runtime primitives are `popCnt#`, `ctz#` and unsigned
`leWord#`. Both the workload and the dedicated primitive fixture must retain all
three in reachable Core. The fixture checks every bit position and its adjacent
values, zero, all-ones, single cleared bits, alternating bit patterns, and
inclusive comparisons at zero, the signed maximum, the sign bit and all-ones.
All values cross the host boundary as their unchanged signed `Int#` bit patterns.
Focused JVM tests also exercise both comparison operands and reject malformed
arities in strict and diagnostic modes.

The IntSet additions contribute 22 workload inputs and 255 inputs for each of
six primitive entries, bringing the complete native/model oracle to 2,546 rows.
The strict IntSet audit accepts 22 reachable bindings with no missing globals
or capability issues; `ctz#`, `popCnt#` and `leWord#` all remain reachable.

This example also exposed excessive AST loop nesting: nonrecursive joins were
being lowered to `LoopNode`, causing Graal escape analysis to exceed the existing
30-second compilation limit. Nonrecursive groups now dispatch once without a
loop; their RHS lexical scopes cannot jump back into the same group. Recursive
joins still use loops, and ancestor transfers, typed results and lazy values
retain their semantics. Catch dispatch structurally excludes the entry body so
opaque exception edges cannot make partial evaluation expand it twice. This
also avoids exceeding the graph-size limit with opt-in handoff transport.
The unchanged workload then passed compilation without
raising limits or adding Haskell optimizer fences. Regression tests exercise
deep acyclic nesting, actual cloned compiled targets, shadowed ancestor jumps,
full-width values and recursive/nonrecursive reference-result laziness.

## Data.Sequence: strict-loadable slices and aggregate frontiers

`THC.SequenceWorkload` exercises ordinary public `Data.Sequence` APIs from the
same pinned, unmodified containers sources. Eleven entries share one post-Tidy
export, with a separate strict audit and explicit support declaration for each
entry. The full bundle's rejected audit cannot hide either supported slices or
unsupported operations. All per-entry reports are fingerprinted artifacts.
The optional `--check-existing build/libraries/cases.json` preparation check
reaudits every entry from fingerprinted Core, enforcing the same declarations,
argument-boundary and source-identity guards as fresh preparation. It does not
regenerate or refresh a manifest. Focused negative tests run in CI and the
library runner.

Four entries have accepted strict audits with no missing globals or capability
issues: `sequenceBuildViews` builds with `fromList` and drains through alternating
left/right views; `sequenceDequeViews` builds through alternating left/right
insertion and observes both drain directions; `sequenceAppendViews` concatenates
unequal sequences in both orders; `sequenceLazyLength` observes length/null with
self-referential, unused lifted payloads at both ends. They retain respectively
19, 18, 23 and 9 reachable definitions. These separate API slices do not replace
the full workload or stand in for fold/split/index support. Static acceptance
does not establish a passing compiled replay on every backend; the current
public-entry blocker is recorded below.

The remaining seven entries deliberately retain their strict unsupported
frontiers. Specialized `foldl'`/`foldr` workers pass genuine zero-width unboxed
`(# #)` **arguments**, so supporting only aggregate results will not unblock
them. Scalar/reference tuple results and tuple-result joins are already supported;
they do not remove those argument boundaries. Split, lookup, index and update
also reach residual exception/state paths, including unsupported `State#` tuple
components, `readMutVar#` and missing exception/backtrace/call-stack/Show
definitions. `quotRemInt#` is now supported and is no longer an audit issue.
The combined strict audit has 160 reachable bindings, 26 issues and four
missing boot-library definitions; all seven rejected entries still retain a
genuine tuple argument or formal-argument boundary. No `main:` source-library
binding is missing. Boxed pairs, triples, unit, views and finger-tree nodes are
not themselves aggregate-ABI failures;
lifted element payloads must stay lazy. Nothing is boxed or substituted to make
a rejected operation appear supported.

The independent oracle uses Python lists/deques, not a second finger-tree
implementation. It checks order-sensitive folds and both drain directions,
both concatenation orders, negative/end/interior split positions, present/missing
lookups and invalid/in-range updates. Each entry has 38 inputs, including empty
and negative sizes, every size from 0 through 17, digit/node carry boundaries,
25/160 bulk-builder boundaries, 255/256/257, 512/1024 and machine-Int extremes.
Sizes are clamped to 0–1024 before arithmetic. The six focused model sanity tests
are available through `python3 scripts/test-sequence-model.py`.
Native GHC agrees with the model on all 418 Sequence rows, bringing the complete
library oracle to 2,964 rows. Of those, 152 new Sequence rows belong to the four
strict-supported slices; the other 266 are native/model coverage of the retained
frontiers, not THC execution claims.

The host compilation API follows active Truffle split targets and also compiles
the stable public host-entry bridge. Compiling only the original target retained
by a closure could leave the actually called clone interpreted. Dedicated tests
force a real host-call split on both backends, verify that both the active clone
and public bridge are installed, and require compiled entry for every 64-bit
boundary input while preserving the closure's original target identity. This
changes neither the guest call ABI nor the compilation limits.

Investigation also observed interpreted public calls despite valid installed
guest code. The shared HotSpot call-boundary stub can be retired independently
of those targets. Explicit compilation now asks the pinned runtime to restore
that prerequisite after compiling the active targets and public bridge, without
executing the guest. This is not sufficient to fix the Sequence replay failure;
the evidence and remaining boundary problem are recorded below. Post-compilation
settling calls did not resolve the failure and have been removed. The checker
retains its fixed warmup and mandatory per-call compiled-entry assertions,
without retries or diagnostic JVM flags.

## Running the checks

Run `scripts/try-libraries.sh` with the pinned GHC and GraalVM environments.
Reports are under `build/libraries/`: per-bundle source provenance and strict
audits, `oracle.tsv`, `oracle-validation.json`, `cases.json`, and explicit AST
and bytecode check logs. Input and artifact fingerprints reject stale examples,
exporter/auditor implementations, capabilities, vendored sources, Core and
native-oracle artifacts. The declared entries and manifest rows must also match
the fingerprinted native oracle. The checker additionally rejects a validation
report with static support violations, a false independent-model result or a
wrong native row count, even when invoked directly after failed preparation.
`scripts/test-library-manifest.py` checks those failures on both backends before
any guest loading; the ordinary library runner includes those tests and the
Sequence model sanity tests. CI runs the checks on Linux and macOS.

The checker keeps strict rejection separate from execution passes and disables
compilation for its interpreted phase. A fresh context warms only the declared
warm inputs, requires successful guest compilation and installed-code entry,
then checks the withheld cold inputs. Cold branches may legitimately invalidate
code. After broad warmup and another compilation request, **every** input must
produce the native result and enter installed guest code. Unsupported traps and
blackholes must remain zero; no diagnostic unsupported mode is needed for
IntMap, IntSet or the primitive entries. The existing Linux/macOS library CI step
prepares all groups and runs both explicit backends; no separate opt-in is needed
for the IntSet workload.

To additionally exercise opt-in dense argument handoff transport, run
`JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true scripts/try-libraries.sh --rerun-tasks`.

The initial Set/IntMap checkpoint was validated on Linux x86-64 with GHC 9.14.1
and GraalVM 25.3.4.1: 225 JVM tests
passed, and each backend passed 2,916 native-oracle comparisons (972 interpreted,
33 compiled-warm, 939 after-compilation cold, 972 final compiled). The 1,005
compiled-warm/final calls per backend each required a positive installed-code
entry counter delta. All six supported entries recorded zero unsupported traps
and blackholes. Set was rejected for its explicit unboxed-tuple representation
on both backends and is excluded from those execution counts. A deliberately
stale source fingerprint was also rejected before guest loading.

The IntSet checkpoint was validated on the same Linux x86-64 toolchain:
242 JVM tests passed in both default and opt-in handoff configurations. Default
AST, default bytecode and opt-in AST handoff each passed 7,572 native-oracle
comparisons: 2,524 interpreted, 72 compiled-warm, 2,452 after-compilation cold and
2,524 final compiled. Each configuration required installed-code entry on all
2,596 compiled-warm/final calls, with zero unsupported traps or blackholes across
all 13 supported entries. The 22 Set oracle rows remain an explicit unsupported
frontier and are excluded from execution counts. A deliberately modified IntSet
expected value in the manifest was rejected against the fingerprinted native
oracle before guest loading. The 30-second compilation timeout and 100,000 graph
size limit remain unchanged.

## Sequence integration validation and current compiled-entry blocker

The integration at `bbc8ee434c31133fd34ec399970f9289faa998af`, based on main
`60c39ffcbec425fa15585a0d7613f27fc75a2b2c`, passed all 310 JVM tests after fresh
source preparation. Native GHC and independent models agreed on all 2,964
library rows. Fresh and reuse audits retained 17 strict-accepted entries and
eight explicit frontiers (Set plus seven Sequence entries); all fingerprints
matched and preparation recorded no static support violations.

Compiled execution remains blocked in the default configurations. These are
separate, unmodified production-checker runs on Linux x86-64 / GHC 9.14.1 /
GraalVM 25.3.4.1, with no post-compilation settling or retries:

| Tested source head | Default AST | Bytecode | AST handoff |
| --- | --- | --- | --- |
| `613dc1f` (main `000dfa3` integration) | Pass | Fail | Pass |
| `bbc8ee4` (main `60c39ff` integration) | Fail | Fail | Pass |
| `3e5ee6d` (explicit boundary-stub restoration) | Fail | Fail | Pass |

Every complete pass includes 8,028 comparisons: 2,676 interpreted, 84
compiled-warm, 2,592 after-compilation cold and 2,676 final compiled. All 2,760
compiled-warm/final calls require positive compiled-entry deltas; all 17
supported-entry diagnostics have zero unsupported traps and blackholes.
Each failure occurs at the first measured `sequenceBuildViews(1)` compiled-warm
call: its result matches the oracle, but its `compiledEntries` counter does not
increase. The preceding 7,572 existing-library comparisons and 38 interpreted
BuildViews comparisons pass. Later Sequence calls are not counted as executed.

A build-only, failure-branch diagnostic at `613dc1f` reproduced the bytecode
miss. Both host and guest targets had valid last-tier code, and the active guest
was the original target, not a stale clone. Calling the same `Value` and input
from a separate cold host site, in the same context and without recompilation,
returned the expected value and increased `compiledEntries` from zero to six.
Target identities, validity and code addresses stayed unchanged. No pre-miss
target/call-count instrumentation was added; call count alone is not proof of
compiled execution. The diagnostic still terminates with the original failure
and is not a replacement coverage pass.

The separate-site result alone does not distinguish a persistent caller-site
problem from a one-call repair. A later build-only probe at `8a91433` reinvoked
the same checker source/bytecode call site after the miss, then a separate cold
site: both entered compiled code, with unchanged target identities, validity and
code addresses. Both probes still terminate with the original assertion. No
claim is made that the source site identifies a particular inlined machine-code
caller.

### Boundary-stub prerequisite experiment

An untouched failing run with HotSpot compilation logging recorded a late
`callBoundary` stub installation immediately after the first Sequence public
call. A separate, more heavily logged run directly observed HotSpot flushing
the original stub as cold while over 117 MB of code-cache space remained. That
second run did not reproduce the failure, so its lifecycle observation must not
be conflated with the failing run or treated as proof of the whole failure path.

At `3e5ee6d3506b6ebbb19322eb91df6eed5c250952`, the explicit compilation operation
invokes the pinned runtime's public `bypassedInstalledCode` hook after compiling
the active guest targets and stable host bridge. This runtime-internal API
installs missing shared boundary code without executing guest code. It is a
GraalVM-version-specific prerequisite, not a guarantee against later VM code
retirement or stale caller links.

The isolated regression retires only `callBoundary` through JVMCI `reprofile()`
while both targets remain valid, then checks restoration **before** the next
public call. Without the repair both backends fail that restoration assertion;
their subsequent tiny guest call already had a positive compiled-entry delta.
Thus the red test demonstrates a missing compilation prerequisite, not a
deterministic recreation of the production zero-delta miss. With the repair both
restoration checks pass. The follow-up test-only commit `9582dad` additionally
checks unchanged host/guest call counts and compiled-entry counts across
compilation. It restores the JVM-wide stub in `finally` and isolates the test
class from parallel execution. Both default and handoff suites pass all 312 tests
with no skips.

The strict replays used the unchanged checker and source/native artifacts from
`8a91433`: all 70 input hashes and 67 artifact hashes matched, and candidate
inputs were identical. The tested runtime JAR SHA256 was
`ce0b5621f91f91461cabb371a386123ce54fb2f4bc939241398cbc7c4b2f46de`.
AST handoff passed all 8,028 comparisons, but default AST and bytecode still
failed the first compiled `sequenceBuildViews(1)` call. A build-only failure hook
on this candidate found last-tier boundary code immediately after the miss,
before any further guest calls; both active targets also remained valid and
unchanged. One diagnostic same-site call and one separate cold-site call each
increased the compiled-entry count by seven, without recompilation. The original
assertion still terminated the run. This post-miss sample does not establish
whether the stub was present before the failed call or repaired during it.

The strict default compiled-replay failures remain a completion blocker; no `Value.execute`
bypass, settling call, relaxed counter requirement, optimizer fence or increased
compilation limit is used to turn them into passes.
