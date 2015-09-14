#include "thc/gc.h"

namespace thc {

using std::uint64_t;

thread_local hec * hec::current;

void gc_ptr::lvb_slow_path(uint64_t * address, int trigger) {
  uint64_t old = addr;

  if (trigger | triggers::contraction) {
    unique = 0;
  }

  // this object was white, make it grey.
  if (trigger | triggers::nmt) {
    nmt = !nmt;
    if (unique) {
      // For anything locally unique we should walk the local subspace, chasing any unique references
      // and doing opportunistic graph reduction. When we consume k unique references we can
      // afford O(k) work. This means we'd act like a semispace copying collector for
      // objects that are known unique, and only ever pass to the marking system things
      // with multiple (potential) references. This also avoids crafting an indirection
      // upon enqueuing a unique closure for marking. It also generalizes Wadler's garbage collection
      // trick for eliminating certain forms of space leaks.
      //
      // This would be sufficient to handle things like a GRIN primitive for + being applied
      // to known integers, for instance.
    } else {
      if (space <= 8) hec::current->local_mark_queue[space].push(*this);
      else global_mark_queue[space - 8].push(*this);
    }
  }

  if (trigger | triggers::relocation) {
    // this page is actively being relocated. cooperate
    // grab a handle to the information about this page that is being relocated
    // try to claim this object
    // if we succeed, and it is not yet relocated, relocate it
    // regardless set addr equal to the new location
  }

  if (trigger | triggers::contraction && type == types::closure) {
    // we're actually going to write this answer into the _closure_ we made

    // addr = new_indirection(addr);
  }

  __sync_val_compare_and_swap(address,old,addr);
}

}
