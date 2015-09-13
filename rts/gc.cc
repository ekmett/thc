#include "gc.h"

namespace gc {

thread_local int gc_ptr::expected_nmt[16]
  = { 0,0,0,0, 0,0,0,0,
      0,0,0,0, 0,0,0,0 };

env_t env;

void gcptr::lvb_slow_path(void * address, int trigger) {
  struct gcptr old = *this;
  if (trigger | triggers.nmt) {
    nmt = !nmt;
    push_mark(*this);
  }
  if (trigger | triggers.reloc) env.relocate(*this);
  __sync_val_compare_and_swap(address,old,*this);
}

}
