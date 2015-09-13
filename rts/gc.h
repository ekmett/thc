#ifndef INCLUDED_RTS_GC_H
#define INCLUDED_RTS_GC_H

#include <cassert>
#include <cstdint>
#include <mutex>
#include <queue>
#include <unordered_set>
#include "thread_local.h"
#include <boost/lockfree/queue.hpp>

namespace thc {

using std::uint16_t;
using std::uint32_t;
using std::uint64_t;
using std::uintptr_t;
using std::mutex;
using std::unordered_set;
using std::unique_lock;

namespace spaces {
  enum {
    external = 0,
    local_min = 1,
    local_max = 7,
    global_min = 8,
    global_max = 15
  };
}

namespace triggers {
  enum {
    nmt = 1,
    reloc = 2
  }
}

namespace types {
  enum {
    constructor        = 0,
    unique_constructor = 1, // duplication turns off this bit, leaving a constructor, no other impact
    indirection        = 2,
    unique_closure     = 3, // duplication requires construction of an indirection. then we stuff this inside of it.
    blackhole          = 4, // this is a blackhole stuffed into an indirection that we are currently executing.
    max_type           = 7  // if we weren't concurrent we could add hash-consing here
  };
}

class hec;

struct gc_ptr;

// global garbage collector configuration

extern mutex gc_mutex;

// contents under the gc_mutex
extern uint32_t regions_begin;    // lo <= x < hi
extern uint32_t regions_end;
extern uint64_t * mapped_regions; // 1 bit per region, packed
extern unordered_set<hec*> hecs;
// end contents under the gc_mutex

extern boost::lockfree::queue<gc_ptr> global_mark_queue[8];

static inline bool protected_region(uint32_t r) {
  assert(regions_begin <= r && r < regions_end);
  return mapped_regions[r>>6]&(1<<((r-regions_begin)&0x3f));
}


struct gc_ptr {
  union {
    // layout chosen so that a 0-extended 32 bit integer is a legal 'gc_ptr' as are legal native c pointers
    // TODO: consider setting space 15 to also be a native pointer that way 0 and 1 extended pointers would
    // be legal.
    uint64_t type     : 3,  // locally unique?
             offset   : 9,  // offset within a 4k page
             segment  : 9,  // which 4k page within a 2mb region
             region   : 19, // which 2mb region in the system? 1tb addressable.
             nmt      : 1,  // not-marked-through toggle for LVB read-barrier
             space    : 4,  // which generation/space are we in?
             tag      : 19; // constructor #
    uint64_t addr;
  };

  // offset and region
  static const uint64_t mask = 0x7ffffffff8;

  template <typename T> T & operator * () {
    return *reinterpret_cast<T *>(addr&mask);
  };

  template <typename T> T * operator -> () {
    return reinterpret_cast<T *>(addr&mask);
  };

  template <typename T> T & operator [] (std::ptrdiff_t i) {
    return *reinterpret_cast<T *>((addr&mask) + (i * sizeof T))
  }
  // TODO: partially template specialize these to make it so gc_ptr loads from those addresses automatically apply the
  // read-barrier?

  // LVB-style read-barrier
  void lvb(uint64_t * address);

  private:
    void lvb_slow_path(gc_ptr * address, int trigger);
};

inline bool operator==(const gc_ptr& lhs, const gc_ptr& rhs){ return lhs.addr == rhs.addr; }
inline bool operator!=(const gc_ptr& lhs, const gc_ptr& rhs){ return lhs.addr != rhs.addr; }

// hecs are haskell execution contexts.
class hec {
  public:
    static thread_local hec * current;
    uint16_t expected_nmt; // 16 bits, one per space
    std::queue<gc_ptr> local_mark_queue[8]; // mark queues for local spaces

  private:
    // we perform raii to bind the current hec, so hide these

    hec(hec const &);               // private copy constructor for RAII
    hec & operator = (hec const &); // private assignment operator for RAII

  public:
    hec() {
      current = this;
      unique_lock<mutex> lock(gc_mutex);
      hecs.insert(this);
    }
    ~hec() {
      current = nullptr;
      unique_lock<mutex> lock(gc_mutex);
      hecs.erase(this);
    }

    inline bool get_expected_nmt(int i) { return expected_nmt & (1 << i); }
};

static inline void gc_ptr::lvb(uint64_t * address) {
  int trigger = 0;
  if (nmt != hec::current->get_expected_nmt(space)) trigger |= triggers::nmt;
  if (space != 0 && protected_region(region))       trigger |= triggers::reloc;
  if (trigger != 0) lvb_slow_path(address, trigger)
}

#endif
