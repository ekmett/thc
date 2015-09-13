#include "gc.h"

namespace gc {

using std::uint64_t;

void gc_ptr::lvb_slow_path(uint64_t * address, int trigger) {
  struct gcptr old = *this;

  if (trigger | triggers.nmt) {
    nmt = !nmt;
    hec::current->mark_queue[space]->push(*this);
  }

  if (trigger | triggers.reloc) relocate()

  __sync_val_compare_and_swap(address,old,*this);
}
