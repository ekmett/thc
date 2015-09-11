#ifndef INCLUDED_RTS_GC_H
#define INCLUDED_RTS_GC_H

#include <cassert>
#include <cstdint>

namespace thc {

enum class spaces : std::int64_t {
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

struct gc_ptr {
  static thread_local int expected_nmt[16];

  union { 
    // layout chosen so that a 0-extended 32 bit integer is a legal 'gc_ptr'
    struct bits {
      std::uint64_t unique   : 1;   // locally unique?
      std::uint64_t indirect : 1;   // an indirection?
      std::uint64_t reserved : 1;   // not currently used
      std::uint64_t offset   : 9;   // offset within a 4k page
      std::uint64_t segment  : 9;   // which 4k page within a 2mb region
      std::uint64_t region   : 19;  // which 2mb region in the system? 1tb addressable memory.
      std::uint64_t space    : 4;   // which generation/space are we in?
      std::uint64_t nmt      : 1;   // not-marked-through toggle for LVB read-barrier
      std::uint64_t tag      : 19;  // constructor
    };
    std::uint64_t addr;
  };

  // offset and region
  static std::uint64_t mask = 0x7fffffffff;
    
  template <typename T> T & operator * () {
    return *reinterpret_cast<T *>(addr & mask);
  };

  template <typename T> T * operator -> () {
    return reinterpret_cast<T *>(addr & mask);
  };

  inline void lvb(void * address) {
    int trigger = 0;
    if (nmt != expected_nmt[space]) trigger |= triggers::nmt;
    if (env.is_protected(region))   trigger |= triggers::reloc;
    if (trigger) lvb_slow_path(address, trigger)
  }

  private:
    void lvb_slow_path(void * address, int trigger);
};

static bool test_bit(std::int64_t * m, int r) {
  return m[r >> 6] & (1<<(r&0x3f));
}

// stuff one of these in thread local storage?
struct gc_env {
  std::int64_t regions_begin; // lo <= x < hi
  std::int64_t regions_end;
  std::int64_t * mapped_regions; // 1 bit per region, packed
  layout * info_layouts;

  static bool in_protected_region(ptr p) {
    auto r = p.region;
    assert(regions_begin <= r && r < regions_end);
    return p.space != 0 && test_bit(mapped_regions,r - regions_begin);
  }
};

}

#endif
