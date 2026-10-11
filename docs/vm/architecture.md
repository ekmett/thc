# Heap architecture

Jam represents references as offsets into two generations. HotSpot represents
compressed oops as offsets from a fixed heap base. With a suitable choice of
address space, these can be the same bits.

That lets us keep jam's allocator, metadata, tracing frontier, rank forwarding,
SIMD compactor and work scheduler. The adapter supplies the representation of a
Java object and the rules for finding its references.

## Cells and objects

A jam cell is eight bytes. Mark metadata covers 32 cells at a time, and a second
mask describes the two possible four-byte pointer slots in each cell. Marking
claims an object's complete extent. Forwarding computes where its live cells
will land by counting live cells before them.

For Java, the VM obtains the extent from `oop::size()` and the pointer locations
from `oop_iterate`. Mark words, Klass pointers and primitive payload remain
opaque data. In particular, a compressed class pointer is not an oop merely
because it occupies four bytes.

There are two separate operations on a field:

* Declare its slot, so the compactor can update it.
* Follow its target, so the target stays alive.

A Java weak referent needs the first operation without automatically getting
the second. The [weak-pointer policy](weak-pointers.md) decides when following
such a target is appropriate.

The VM batches narrow slots in groups of 128 before calling the backend.
There is no foreign call for each pointer in a Java object. Wide fields, such
as references in stack chunks, are traced through jam but repaired by the VM
before copying. Their cells remain ordinary payload to the SIMD kernel.

## A stable view of a rotating heap

Each jam generation uses a double-mapped circular buffer. The second mapping
makes a logical arc contiguous even when it crosses the physical end of the
buffer. After compaction, the generation adopts a new origin in that ring.

Compiled Java code cannot follow a changing compressed-oop base. Instead, the
VM reserves a fixed canonical address range. The backend maps the current
logical arc of each ring into that range and republishes it after collection.
The ring's private aliases retain jam's overlap and copy-credit invariants.
The canonical alias gives Java a stable decoding rule.

On Windows, the VM reserves these addresses with placeholders. Jam replaces
only the managed windows with views of its existing backing sections. Moving
the ring origin changes the views without releasing the outer address range.
Destroying the backend restores the placeholders before the VM releases that
reservation.

Let `B` be the compressed-oop base and `i` a nonnull cell offset. With shift
three, HotSpot decodes it as:

```text
decode(i) = B + 8*i
```

Jam uses bit 31 to select young. Consequently:

```text
D = 8 * 2^31 = 16 GiB

decode(old(i))   = B     + 8*i
decode(young(i)) = B + D + 8*i
```

The gap is reserved virtual address space. It is not a 16 GiB allocation of
physical memory. Only the usable generation windows are published; their
private copy reserves and protected prefixes are not Java heap capacity.

| Quantity | Meaning |
| --- | --- |
| `B` | Fixed, nonzero compressed-oop base |
| `P` | Protected prefix before each usable generation |
| `O`, `Y` | Usable old and young capacity |
| `R` | Each generation's private copy reserve |
| `B + P` | First usable old address |
| `B + D + P` | First usable young address |
| `O + Y` | Reported Java heap capacity |

Each private ring needs room for its prefix, usable capacity and copy reserve
within the 16 GiB generation domain. The prefix is retained during compaction
but never scanned as Java objects. Promotion excludes the young prefix and
recreates it when the nursery resets. Cell zero represents null.

For a valid nonnull published address `p`, the encoding must satisfy:

```text
decode(encode(p)) = p
encode(decode(i)) = i
forward(0)       = 0
```

The first two laws hold only for usable published addresses and valid offsets;
the protected prefix and unmapped gap do not contain objects. A surviving
reference becomes `forward(i)` before mutators resume. Publishing the next
alias changes which backing pages those bits address, without a second payload
copy.

There is a useful consequence of choosing identical reference bits: jam's
existing SIMD forwarding code needs no conversion. There is also a trap.
A *slot index* counts four-byte positions, so doubling a young cell index
needs 33 bits. The C interface uses 64-bit slot indices even though the
references stored in those slots are still 32 bits.

## Minor collection

New objects and TLABs normally allocate in young. A retaining minor marks and
compacts young, leaving old addresses and old ring state unchanged:

```text
minor_forward(old(i)) = old(i)
```

Ordinary old roots are treated as live without traversing their objects.
Write barriers record exact old source slots, their encoding, and the holder
needed for Java reference policy. Generated stores check the destination address
before entering the registration helper; young destinations need no registration.
The helper validates the old range for its other callers. Barriers do not read
the concurrently mutable value. Array copies register their range with one registry lock; their slots
are known strong references and need no holder. Instance fields retain the
holder so a `Reference.referent` is never accidentally treated as a strong edge.

At the safepoint, a minor snapshots that sparse registry and traces its current
young targets. Reference processing decides which weak targets survive. The VM
repairs stationary old slots before Jam moves young objects. A retaining minor
keeps surviving old-to-young entries: the next collection must find them even
when no intervening store occurs.

Preparation prunes and relocates the registry while original slots and forwarding
metadata are still available. A major clears it first; the ordinary marker
records surviving old fields again. There are no dirty cards, block-offset
tables, owner rescans, or post-collection old-heap reconstruction passes.

Continuations use the VM's existing reference visitor and frame maps. HotSpot
retires remembered slots when its stack pointer advances and when existing thaw
code discards argument intervals. This erases four-byte source keys without
reading discarded payload or walking the continuation again. Native Image
publishes copied continuation references through its existing after-fill hook.

## Promotion

Every `JamPromoteEvery` minors, the collector attempts to promote the complete
live nursery into old. Promotion is checked after reference policy has closed,
when the final live size is known.

If the survivors do not fit, preparation fails before forwarding roots or weak
registrations. The same epoch then prepares a retaining minor. Changing that
failure into a major at this point would change the liveness question after
weak decisions had already been frozen.

A successful promotion resets young and clears the exact remembered set: there are no surviving young objects left to remember.
Whole-nursery promotion is deliberate; selective promotion is a non-goal for now.

## Major collection

A major marks and compacts both generations independently. It does not require
the combined live heap to fit in old space. Both forwarding tables remain
available while roots, fields and weak metadata are repaired, and both moves
finish before either generation's metadata is packed.

The marker rebuilds exact remembered entries as it visits old fields. For example, a 64 MiB heap with 32 MiB old can
retain over 40 MiB of live objects across a major, even though it cannot
promote them all into old.

## What belongs on each side

| Concern | jam | VM adapter |
| --- | --- | --- |
| Storage | Physical backing and private rings | Stable canonical reservation |
| Allocation | Cell allocation | Object initialization, TLABs and failure policy |
| Liveness | Full-extent claims and mark metadata | Object sizes, roots and reference policy |
| Tracing | Frontier and persistent worker pool | Object scanners and exact remembered slots |
| Relocation | Rank forwarding, SIMD movement and mask packing | Root repair, wide fields and code relocations |
| Scheduling | Copy waves and bounded reserve | Safepoints, minor/major selection and promotion attempts |

The adapter borrows a `jam::heap::host` to control the collection phases.
Jam's heap, compactor and worker pool are used without a local patch. VM
scanning currently runs on the registered VM thread; native copy work uses
jam's worker pool. Parallel VM scanning needs a proper HotSpot
worker-registration contract first.

Each VM thread owns a descriptor for a `jam::heap_scope`. HotSpot enters that
scope on the executing OS thread and leaves it on detach. Native Image also
suspends the scope across native calls, allowing an OS thread to switch
isolates. Jam's own tracing workers retain their existing heap scopes.

The current encoding requires fixed capacities, ordinary object headers,
eight-byte alignment, normal pages and nonzero-base shift-three compressed
oops. Other layouts are separate implementation work, not alternate settings
of this adapter. See [HotSpot integration](hotspot-integration.md) for validation
and [Native Image](native-image.md) for the SubstrateVM layout and pinning rules.

## Source

The implementation uses Jam's
[heap](https://github.com/ekmett/jam/blob/main/src/jam/heap.ccm),
[compactor](https://github.com/ekmett/jam/blob/main/src/jam/packed.ccm)
and [work scheduler](https://github.com/ekmett/work/blob/main/src/work.ccm).
The [source manifest](https://github.com/ekmett/thc/blob/main/src/vm/config/source-pins.json) records the exact inputs.

## Optional object starts

The hosted heap can transport an object-start bitmap for VM heap enumeration.
It is disabled by default. `thc_vm_track_starts` allocates the side channel;
allocation failure returns zero without enabling it. The VM then runs a major
collection whose tracer calls `thc_vm_record_start` after each successful object
claim. Raw allocations can contain many Java objects, so `thc_vm_allocate` does
not invent a start for a TLAB. When tracking is enabled, the VM also records each
new object before it can be exposed to a safepoint.

`thc_vm_start_bits` borrows the current canonical bitmap through the generation's
allocated high-water mark: one bit per eight-byte cell, returned in 32-bit words.
Reacquire the view after each collection and inspect it only with mutators stopped.
The tracer supplies the meaning of the bits; core Jam does not decode Java headers.
Both VM adapters enable tracking before their first diagnostic heap walk and
take a major collection to establish starts, even when the request normally
skips collection. HotSpot prepares on the requesting thread before the diagnostic
operation retains raw object pointers. Its interpreter, C1, C2, Graal and slow
allocation paths then record new starts; Native Image uses its allocation
snippets. Later walks use the maintained bitmap without an extra collection.
HotSpot verification flags enable tracking at startup because verification can
run before a diagnostic request. TLAB allocation remains enabled.

The metadata packer has `template<bool tracks_starts>` specializations. The false
version never accesses start bits. The true version relocates them in the existing
pointer-mask packing pass, using the same retained-cell ranks and destination
positions as payload. Promotion preserves starts already in the old destination's
partial mini-page. Claims for a collected generation replace its previous starts;
a stationary old generation retains its bitmap during a minor collection.

Pinned-page compaction transports starts in its existing payload/metadata loop.
Retained padding gets no start bit. That path uses a fresh zeroed destination
bitmap alongside its fresh destination metadata; promotion appends directly into
the stationary old bitmap. There is no additional object traversal. Enabling
tracking costs one byte per 64 bytes of arena capacity, plus temporary destination
bitmaps while moving pinned pages. None of these bitmaps exists in an untracked heap.
