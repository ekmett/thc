#include <thc/gc.h>

namespace gc {

thread_local int gc_ptr::expected_nmt[16]
  = { 0,0,0,0, 0,0,0,0,
      0,0,0,0, 0,0,0,0 };

env_t env;

-- use thread local storage to hold onto meta info

void gcptr::read_barrier(void * address) {
  int trigger = 0;
  if (nmt != expected_nmt[space]) trigger |= triggers.nmt;
  if (env.is_protected(region)) trigger |= triggers.reloc;
  if (trigger != 0) read_barrier_slow_path(address, trigger)
}

void gcptr::read_barrier_slow_path(void * address, int trigger) {
  struct gcptr old = *this;
  if (trigger | triggers.nmt) {
    nmt = !nmt;
    push_mark(*this);
  }
  if (trigger | triggers.reloc) env.relocate(*this);
  __sync_val_compare_and_swap(address,old,*this);
}

}
