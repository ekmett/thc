# VM finalizer safety

Lean models of Jam's generalized weak registry, audited at
[`8042f8b`](https://github.com/ekmett/jam/tree/8042f8bf36b6c09b4cf555787420db43dd7524a7).
The registry and public `Weak` API are unchanged at `9b1bd59`, the integration
base. Source line references below refer to the audited revision:

- [Registry](https://github.com/ekmett/jam/blob/8042f8bf36b6c09b4cf555787420db43dd7524a7/vm/src/thc/jam/weak.ccm)
- [Java API and pump](https://github.com/ekmett/jam/blob/8042f8bf36b6c09b4cf555787420db43dd7524a7/vm/src/bridge/java/jam/vm/Weak.java)
- [Native Image collection](https://github.com/ekmett/jam/blob/8042f8bf36b6c09b4cf555787420db43dd7524a7/vm/src/graal/substratevm/src/com.oracle.svm.core.jam/src/com/oracle/svm/core/jam/JamGC.java)
- [Native Image API](https://github.com/ekmett/jam/blob/8042f8bf36b6c09b4cf555787420db43dd7524a7/vm/src/graal/substratevm/src/com.oracle.svm.core.jam/src/com/oracle/svm/core/jam/Target_jam_vm_Weak.java)
- [HotSpot integration](https://github.com/ekmett/jam/blob/8042f8bf36b6c09b4cf555787420db43dd7524a7/vm/patches/hotspot-jam.patch)
- [Core collector and host liveness](https://github.com/ekmett/jam/blob/8042f8bf36b6c09b4cf555787420db43dd7524a7/src/jam/heap.ccm)

From the repository root:

```sh
python3 lean/finalizers/check.py
```

The parent `lean-toolchain` pins Lean 4.24.0. The proofs use bundled `Std` and
no additional packages. The checker builds four modules in a fresh temporary
directory, treats warnings as errors, and audits 25 theorem dependencies. It
writes an ignored `verification.txt` with the commands, results and input hashes.
Dependencies are limited to Lean's standard `propext`, `Classical.choice`, and
`Quot.sound`. There are no admitted proofs, custom axioms, or native evaluation
shortcuts. The checker verifies the models; it does not prove refinement of
whichever C++/Java revision is checked out.

## Machine-checked coverage

| Model | Claims | Source correspondence |
| --- | --- | --- |
| `Lifecycle.lean` | Every serialized command history claims a registration incarnation at most once; retirement cannot rearm it; dereference remains null after retirement. | `weak.ccm` lines 104–123, 135–165: freeze, take, finalize, complete. |
| `Lifecycle.lean` | Queued/running finalizers retain their registry root through arbitrary nested collection observations and competing claims, until a completion command. | `roots` at line 91 and running-state guard in `complete` at line 158. |
| `Closure.lean` | Each batched activation/trace round is sound; an empty-selection exit equals the least conditional reachability fixed point; changing registration order cannot change that result. | `close` activation loop at lines 106–117. |
| `Closure.lean` | Unrooted conditional cycles cannot activate themselves; more general closed unreachable regions remain unreachable. Conservative old-key liveness adds retention. | Live-key gating in `close`, plus collector-supplied minor liveness. |
| `Batch.lean` | Freezing against one marking result is independent of entry order; later finalizer-to-key rescue cannot reactivate a retired registration. | `close` retirement loop at lines 118–123; VM traces newly queued finalizers afterward. |
| `Tokens.lean` | Slot/generation encoding is injective and positive within signed 64-bit range; reuse strictly increases generation; maximum generation retires permanently; old tokens cannot match or complete a recycled occupant. | `find`, `token`, `remove` at lines 34–53. |

The lifecycle proof derives a decreasing claim budget from the transition
function. It does not put an at-most-once condition into the transition
premises. Its command sequences allow arbitrary interleavings of polls,
explicit finalization, collection observations, and completion.

The closure model constructs ordinary strong reachability and conditional
reachability inductively. It models each scan as selecting previously
unactivated live keys, followed by a drained trace of their nonnull value and
finalizer fields. It proves an invariant of these rounds and derives leastness
at loop exit. Leastness and registration-order independence are conclusions,
not assumptions. It does not yet prove a finite iteration bound or termination
of the collector's trace callback.

The batch model retains dead entries as tombstones. Actual C++ removal uses a
dense live-index vector and swaps its tail. The proofs concern registration
observations, not that vector's representation invariant. The reachability
model may collapse identical active `(K,V,F)` triples; multiplicity does not
change reachability. The lifecycle and token results concern separate
registration incarnations, so identical triples do not share a claim budget.

## Checked counterexamples and limits of the API

`early_trace_changes_second_decision` and
`finalizer_backedge_rescues_object` exhibit two registrations sharing an
initially dead key. Freezing both retires both. Tracing the first finalizer's
backedge before deciding the second can keep the second active. This explains
why the complete batch must be frozen; it is not a defect report against the
current VM implementation.

`premature_complete_releases_root` shows that claim followed immediately by
completion releases the root, irrespective of whether a client has executed
the callback. The registry cannot enforce execution ownership. An asynchronous
consumer must keep the original claim until actual execution finishes or it
deliberately abandons/transfers failure handling with an adequate replacement
root. Tokens are not security capabilities.

`hung_callback_stays_running` permits arbitrarily many collections while the
callback remains running. This is the correct retention behavior, not a
termination guarantee. Java `Weak.pump` calls `complete` in `finally`; an
exception releases the current claim and propagates, leaving other finalizers
queued. Thus even the pump does not promise successful cleanup or draining
the whole queue. A client can also invoke a returned Runnable twice; the
proved bound is on registry claims, not arbitrary client invocations.

## Exact implementation obligations

These are model proofs, not a C++/Java refinement proof. In particular:

1. **Serialized access.** Each modeled command must execute atomically with
   respect to other registry operations and collection. Native Image uses the
   heap lock around create/deref/take/finalize/complete in
   `Target_jam_vm_Weak.java`; HotSpot uses `Heap_lock` in `JVM_JamWeak*`.
   The C adapter itself adds no lock. Lock behavior, safepoint exclusion and
   the C++ memory model are not formalized here.
2. **Exact marking and drained tracing.** Trace callbacks must be synchronous,
   nonreentrant, and unable to mutate this registry. The fixed-point model
   assumes a stable strong-edge relation during closure and complete tracing
   of supplied roots. Java reference discovery/processing is represented by
   the effective edge relation and initial roots; its coupling to HotSpot's
   ReferenceProcessor and Native Image reference passes is not proved.
3. **Roots and forwarding.** A queued/running entry contributes its finalizer
   to `roots`; the runtime must actually trace that root and repair it through
   movement. Lifecycle object numbers denote stable logical identities.
   The model does not implement `weak_registry::forward`, compressed-pointer
   decoding, host handles, stack maps, or object movement. Valid queued/running
   entries have nonnull finalizers; null-preserving and live-identity-preserving
   forwarding must maintain that property.
4. **Pins and minor collection.** `host::alive` uses the exact `traced` snapshot
   when pin placement expands occupancy. Padding must not become semantic
   liveness. This is an unproved collector obligation here. No non-expansion,
   monotone compaction slack, capacity, alias-coherence, or pin-placement claim
   follows from these finalizer proofs. Old-key conservatism is modeled as
   extra logical roots; the minor collector's barriers/remembered sets must
   supply the corresponding effective young tracing closure.
5. **Storage and finite widths.** Token arithmetic uses naturals with the
   actual 32-bit slot and 31-bit generation bounds. The encoding stays below
   `2^63`; source refinement of casts, zero-low-word unsigned underflow,
   free-list integrity, dense-vector swapping, array bounds and allocation
   behavior is not proved. In C++, low word zero decodes to `0xffffffff`, which
   cannot index the permitted entries vector. Each new incarnation starts a
   new lifecycle; the no-rearm theorem concerns an existing token.
6. **Progress.** No fairness, pump scheduling, guest-context admission, callback
   termination, or eventual successful cleanup result is claimed. Object
   enumeration, forwarding, and heap-layout implementation are outside this
   model; their correctness is not established by these lifecycle proofs.

Native Image's relevant order is visible in `JamGC.java` lines 185–193:
queued/running roots, weak closure and freeze, early Java-reference processing,
newly dead finalizer roots, then later reference passes. HotSpot's patch has
the corresponding weak closure and pre-final-keepalive hook. These are inspected
source correspondences; the joint Java/guest reference policy has not been
machine-checked.

## Registration failure: source argument, not a Lean proof

`create` performs potentially failing growth before publishing a registration:
it may append one dead slot, then reserves the live-index and scratch buffers.
It does not unlink a recycled slot or write the new association until both
reserves succeed. On `bad_alloc` or `length_error`, it pops the tentative appended
slot, if any, and returns zero. Existing association fields and cleanup states
are therefore unchanged, assuming the standard vector exception guarantees
for these trivial element types. Capacity and physical storage may change;
the guarantee is semantic preservation, not byte-for-byte container identity.
After successful reserves, appending the trivial live index does not allocate.

This argument was checked against `weak.ccm` lines 56–81 and the
[allocation-failure fixture](https://github.com/ekmett/jam/blob/8042f8bf36b6c09b4cf555787420db43dd7524a7/vm/t/weak-registry-test.cc).
It was not formalized by inventing an atomic
transaction primitive that assumes the desired result. Native fixtures and
runtime tests were not rerun in this proof task. A full proof would model the
vector/free-list representation and exceptional control flow.

## Core C++ is a different policy

`src/jam/heap.ccm::retain_weak` immediately retains a dead registration's key
and finalizer. `run_collection` processes sorted registrations and drains
tracing after each one. An earlier dead registration can consequently retain
a later key. The VM registry instead reaches the live-key fixed point, freezes
all dead registrations, and then traces their finalizers; it does not retain
a dead key merely because that key had a finalizer.

These proofs establish the VM model's laws. They do not silently impose them
on core C++ finalization, prove the two policies equivalent, or treat their
intentional difference as an implementation bug.
