#ifndef INCLUDED_RTS_GC_H
#define INCLUDED_RTS_GC_H

#include <boost/thread.hpp>
#include <cassert>
#include <cstdint>

namespace thc {

enum class spaces : std::uintptr_t {
  external   = 0, // null pointers, integers, 32-bit floats, etc.
  local_min  = 1,
  local_max  = 7,
  global_min = 8,
  global_max = 15
};

enum class triggers : int {
  nmt = 1,
  reloc = 2
};

enum class types : std::uint64_t {
  constructor        = 0,
  unique_constructor = 1, // duplication turns off this bit, leaving a constructor, no other impact
  indirection        = 2,
  unique_closure     = 3, // duplication requires construction of an indirection. then we stuff this inside of it.
  blackhole          = 4, // this is a blackhole stuffed into an indirection that we are currently executing.
  max_type           = 7  // if we weren't concurrent we could add hash-consing here
};

class core;

class gc {

  gc(gc const &);               // private copy constructor for RAII
  gc & operator = (gc const &); // private assignment operator for RAII

  // global garbage collector configuration
  std::uint32_t regions_begin;    // lo <= x < hi
  std::uint32_t regions_end;
  std::uint64_t * mapped_regions; // 1 bit per region, packed

  friend core;
  void register(core &);
  void unregister(core &);

  public:
    gc();  // allocate a growable system heap, add parameters here
    ~gc(); // return memory to the system

  static inline bool in_protected_region(ptr p) {
    auto r = p.region;
    assert(regions_begin <= r && r < regions_end);
    return p.space && mapped_regions[r>>6]&(1<<((r-regions_begin)&0x3f));
  }

}

// cores are haskell execution contexts.
class core {
  private:
    std::uint16_t expected_nmt; // array of 16 bits
    gc & system_;
    static extern boost::thread_specific_ptr<core> current;

    core(core const &);               // private copy constructor for RAII
    core & operator = (core const &); // private assignment operator for RAII

  public:
    core(gc & system) : system_(system) {
      current.reset(this);
      system.register(*this);
    }
    ~core() {
      system.unregister(*this);
      current.release();
    }

    // perform raii to bind to the current_core

    inline bool get_expected_nmt(int i) { return expected_nmt & (1 << i); }
};

struct gc_ptr {

  union {
    // layout chosen so that a 0-extended 32 bit integer is a legal 'gc_ptr'
    // as are legal native c pointers
    std::uintptr_t type     : 3,  // locally unique?
                   offset   : 9,  // offset within a 4k page
                   segment  : 9,  // which 4k page within a 2mb region
                   region   : 19, // which 2mb region in the system? 1tb addressable memory.
                   space    : 4,  // which generation/space are we in?
                   nmt      : 1,  // not-marked-through toggle for LVB read-barrier
                   tag      : 19; // constructor #
    std::uintptr_t addr;
  };

  // offset and region
  static std::uintptr_t mask = 0x7ffffffff8;

  template <typename T> T & operator * () {
    return *reinterpret_cast<T *>(addr & mask);
  };

  template <typename T> T * operator -> () {
    return reinterpret_cast<T *>(addr & mask);
  };

  inline void lvb(void * address) {
    int trigger = 0;
    core current = *core::current;
    if (nmt != current->get_expected_nmt(space)) trigger |= triggers::nmt;
    if (current->gc.is_protected(region))   trigger |= triggers::reloc;
    if (trigger) lvb_slow_path(address, trigger)
  }

  private:
    void lvb_slow_path(void * address, int trigger);
};

#endif
