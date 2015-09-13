#include "gc.h"

namespace gc {

using std::uint64_t;

void gc_ptr::lvb_slow_path(uint64_t * address, int trigger) {
  struct gcptr old = addr;

  // this object was white, make it grey.
  if (trigger | triggers.nmt) {
    // TODO: anything local unique which contains no pointers to mark could maybe move to to-space eagerly.
    nmt = !nmt;
    if (space <= 8) hec::current->local_mark_queue[space].push(*this);
    else global_mark_queue[space - 8].push(*this);
  }

  if (trigger | triggers.reloc) {
    // this page is actively being relocated. cooperate
    // grab a handle to the information about this page that is being relocated
    // try to claim this object
    // if we succeed, and it is not yet relocated, relocate it
    // regardless set addr equal to the new location
  }

  __sync_val_compare_and_swap(address,old,addr);
}
