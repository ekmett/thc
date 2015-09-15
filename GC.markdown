## Garbage collection

Operational Thesis:

I want a heap:

1.) which doesn't have to use header words per object

2.) which doesn't stop the world

3.) which doesn't have to pay for full semispace storage overhead to improve locality

4.) which doesn't pay for forwarding pointers for unique data, which let's me generalize Wadler's space leak fix to do much broader forms of opportunistic evaluation

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

e.g. How do we eliminate indirections? We can shove indirections into special pages that use copy-collection, and use the target type to eliminate them once they become data constructors.

Working model:

Use a loaded value barrier as a read-barrier. Modify it to track uniqueness. Unlike Wise/Friedman, don't bother to recover uniqueness during mark for now, we can't stop the world. We can recover uniqueness locally by stepping through an indirection. Investigate later if we can recover uniqueness during GC.

Observation: If an indirection points to an 8 byte block of memory that we will replace with a blackhole with an _ironclad guarantee_ that nobody else will enter the thunk while we evaluate it, then we can store a _unique_ pointer in a non-unique indirection and retain that property. During evaluation of it we remain locally unique. In compressor terms, if we copy the indirections to to-space they become require-update pages.

Wadler's trick becomes two smaller tricks: a unique selector referencing a data constructor can forward itself. a non-unique selector referencing a data constructor is a chain of an indirection referencing a selector, referencing the data constructor. we can evaluate this with a variant of the blackholing process, either by inserting a blackhole as usual into the indirection, then copying the value from the selected field, or by directly compare-and-swapping the contents of the field in for the indirection.

We need to rendezvous between threads occasionally. We could a.) actually rendezvous which requires a full stop, killing worker productivity or b.) do a baton pass, everybody says they've confirmed the transition, then we switch modes when we know everyone has signed off.

One nice trick in the C4 barrier was the reservation of space 0 for non-heap pointers. Done here it means we can treat a 0-extended 32-bit integer as a valid 'heap' pointer, just by being careful when we trace. This makes it _much_ easier to opportunistically evaluate additions and the like on 32-bit or smaller integers without wasted memory accesses.

I'm generally proposing that holding a reference from an older (or more global) generation to a newer generation pins the object in place. We could memory map a copy of that page in the oldest generation, copy out all the other garbage, and since this is the _same page_ just mapped twice mutations that happen to one happen to both, but if our data was inert we could just copy the darn thing over without ceremony, so long as we had a way to track the forwarding of the page to fix up old references.

Working thoughts:

We seem to have a few different kinds of things when we break apart the cases. Each of them is suitable to a different scheme.

* Unique closures, these effectively empower the owning thread to do whatever it likes with them.
* Data constructors / immutable objects, these may almost be freely replicated as we move them. If they are unique we can do what we will with them. If they are non-unique we may need to decide how to deal with closures in them. the act of not-moving them may require us to degrade a thunk contained inside one to go through an indirection rather than be unique, this would happen when we enter the read-barrier in a non-unique context, visiting a data constructor that believes it is unique, while nmt is flagged.
* Indirections, these can hold unique closures or be blackholed, they are very small: always 8 bytes, we can copy collect these pages.
* Mutable objects, these can hold references to other generations without implying promotion. These will always be expensive, and need card marking, can we treat this space specially?

Most data is local. Each HEC can use whatever scheme it wants to manage local heaps. Concurrent? Stop-the-world? It makes sense to be able to vary the strategy, then we can have the user interface thread or the one running OpenGL, etc. to be locally concurrent. It'd be nice to be able to schedule a concurrent global-style GC for local pages even, by using hyperthreading to keep it on the same core, but its hard to schedule that accurately. Can we use [thread_affinity](https://developer.apple.com/library/mac/releasenotes/Performance/RN-AffinityAPI/) to set this sort of game up / pair mutator threads up with collector threads in general for the global heap as well?
