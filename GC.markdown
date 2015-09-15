# Garbage Collection

Operational Thesis:

I want a heap:

1.) which doesn't have to use header words per object

2.) which doesn't stop the world

3.) which doesn't have to pay for full semispace storage overhead to improve locality

4.) which let's me generalize Wadler's space leak fix to do much broader forms of opportunistic evaluation

5.) which lets me determine which case I'm evaluating on the other side of the prefetch overhead of reading the target of a pointer.

It'd be nice to get benefits in terms of the underlying evaluation model to avoid megamorphic jumps to unknown addresses, while I'm at it.

Some relevant papers:

* [C4: The Continuously Concurrent Compacting Collector](http://www.azulsystems.com/sites/default/files/images/c4_paper_acm.pdf) by Gil Tene et al. 2011 gives a good working description of a loaded-value barrier.

* [The Compressor: Concurrent, Incremental, and Parallel Compaction](http://www.cs.utexas.edu/~speedway/fp031-kermany.pdf) gives a good exploration of a concurrent compaction algorithm, and a good working summary of using bitvectors for marking and offset calculation. This is definitely suitable for forwarding data constructors, even if it isn't ultimately suitable for forwarding situations, such as thunk evaluation.

* [Stop-and-copy and One-bit Reference Counting, Technical Report 360](http://www.cs.indiana.edu/pub/techreports/TR360.pdf) by David Wise 1993 talks about one-bit uniqueness tracking in the pointer or in the object.

  * Notably: When the garbage collector encounters a unique reference in from space during scan it copies to to space but doesn't leave any forwarding as it holds the only reference.
  * They also do a good deal of work to _recover_ uniqueness during the mark phase. Can we steal that work by performing it on c4/compressor-style out-of-band bitvectors?

* [Fixing some space leaks with a garbage collector](http://homepages.inf.ed.ac.uk/wadler/topics/garbage-collection.html) by Philip Wadler 1987 talks about how to fix up a class of space leaks by forwarding field selectors applied to evaluated data constructors during the walk of a garbage collector.

  * In theory we can generalize this: If we visit n unique words on the heap to do work during the opportunistic promotion during the mark phase we can perform O(n) work simplifying them. e.g. Given a primitive addition node and two references that are evaluated integers we can evalute the addition as its cost is linear in the amount of heap space involved.

* [A concurrent, generational garbage collector for a multithreaded implementation of ML](http://gallium.inria.fr/~xleroy/publi/concurrent-gc.pdf) by Doligez and Leroy 1993 and [Parallel generational-copying garbage collection with a block-structured heap](http://research.microsoft.com/en-us/um/people/simonpj/papers/parallel-gc/) by Marlow et al. describe schemes that use local collection then rendezvous with a global scheme. Each with a different flavor. We want to be able to use local collections where possible. Most garbage is created local and stays local, paying to sync up globally to work with it is a losing game.

* [Sapphire: Copying GC Without Stopping the World](https://people.cs.umass.edu/~moss/papers/jgrande-2001-sapphire.pdf) by Hudson and Moss 2001 plays games that ensure multiple copies of the same data remain in sync, but decrease read overhead.  Some data is just fully inert. e.g. an Int64 would be a pointer to 8 bytes of inert data. We need to copy it, but we damn well don't need to coordinate that copying! Can we say the same thing about every data constructor in `*`? We'd potentially have to 'self-heal' twice, but any data constructor can live in multiple places. If that is (almost) all our non-indirection based garbage we could have a very very cheap garbage collector indeed.

No one of these solutions in isolation is the answer, so let's throw things into a blender.

We should try to break things apart into different cases based on what sorts of objects actually live on the heap:

## Unique Data

Data that has a unique incoming reference can be moved or mutated in place by whomever gets a pointer to it.

## Data Constructors

Data constructors are completely inert, but contain pointers to other data.

Invariant: THEY ARE NEVER MUTATED.

This means we can implement an concurrent, generational, cooperative mark-compact collector for these very easily.

Consider a mark-compact form with, per page meta-data:

   local start bits, local forwarding pointer
   global start bits, global forwarding pointer
   common end bits
   next local page (circular list)
   next global page (circular list)
   up to 32 bytes of other meta-data


This gives 256 bytes of metadata per 4k page, (1/16th overhead) in exchange for being able to use a mark-compact algorithm but without any real synchronization
beyond a CAS to set a forwarding pointer, and a requirement that every local collection complete at least once per global collection.
If we wanted to allow aging as well, we'd still hit less than 1/8th overhead, if we only allow older generations in the global heap then we have enough room to do this with about 1/12th overhead with only one extra forwarding pointer by merging the local/global/old start-bits into something with 2-bits per word.

## Closures

We need to be able to forward these to arbitrary addresses. This implies we want a forwarding pointer per object. This opens us up to a couple of strategies. We can use a direct copying collector for this, or we can mark-then-copy. The latter would permit us to store the forwarding pointer per object off to one side. allowing us to reclaim the pages early like the other mark-compact collections involved around here. That puts us on a similar clock and lets us share the marking code.

## Mutable Objects

These suck. Basically we're in Java land here, everything hurts and is expensive.

We can use a concurrent mark-compact, but it means our relocation costs during the read-barrier are much higher and involve lots of compare-and-swap noise.

On the plus side we can share marking code.

## Working model:

Use a loaded value barrier as a read-barrier. Modify it to track uniqueness. Unlike Wise/Friedman, don't bother to recover uniqueness during mark for now, we can't stop the world. Closures recover uniqueness of their contents. TODO: Investigate later if we can recover uniqueness during GC.

Wadler's trick becomes two smaller tricks: a unique selector referencing a data constructor can forward itself. a non-unique selector referencing a data constructor is a chain of an indirection referencing a selector, referencing the data constructor. we can evaluate this with a variant of the blackholing process, either by inserting a blackhole as usual into the indirection, then copying the value from the selected field, or by directly compare-and-swapping the contents of the field in for the indirection.

We need to rendezvous between threads occasionally. We could a.) actually rendezvous which requires a full stop, killing worker productivity or b.) do a baton pass, everybody says they've confirmed the transition, then we switch modes when we know everyone has signed off.

One nice trick in the C4 barrier was the reservation of space 0 for non-heap pointers. Done here it means we can treat a 0-extended 32-bit integer as a valid 'heap' pointer, just by being careful when we trace. This makes it _much_ easier to opportunistically evaluate additions and the like on 32-bit or smaller integers without wasted memory accesses.

~~I'm generally proposing that holding a reference from an older (or more global) generation to a newer generation pins the object in place. We could memory map a copy of that page in the oldest generation, copy out all the other garbage, and since this is the _same page_ just mapped twice mutations that happen to one happen to both, but if our data was inert we could just copy the darn thing over without ceremony, so long as we had a way to track the forwarding of the page to fix up old references.~~ (This is replaced with the scheme above where we hold both old and new generation bitmaps in the data constructor pages)

Working thoughts:

We seem to have a few different kinds of things when we break apart the cases. Each of them is suitable to a different scheme.

* Unique closures, these effectively empower the owning thread to do whatever it likes with the memory for them.
* Unique data constructors can be copied by the process that visits them. You can do what you like with their memory as well afterwards.
* Data constructors, can be mark-compacted without any coordination overhead beyond picking the forwarding pointer for the whole block.
* Mutable objects, these can hold references to other generations without implying promotion. These will always be expensive, and need good card marking.

Most data is local. Maybe each HEC can use whatever scheme it wants to manage local heaps. Concurrent? Stop-the-world? It makes sense to be able to vary the strategy, then we can have the user interface thread or the one running OpenGL, etc. to be locally concurrent. It'd be nice to be able to schedule a concurrent global-style GC for local pages even, by using hyperthreading to keep it on the same core, but its hard to schedule that accurately. Can we use [thread_affinity](https://developer.apple.com/library/mac/releasenotes/Performance/RN-AffinityAPI/) to set this sort of game up / pair mutator threads up with collector threads in general for the global heap as well?
