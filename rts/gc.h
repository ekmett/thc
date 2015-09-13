#ifndef INCLUDED_RTS_GC_H
#define INCLUDED_RTS_GC_H

#include <cassert>
#include <cstdint>
#include <mutex>
#include <unordered_set>
#include "thread_local.h"

namespace thc {

enum class spaces : std::uintptr_t {
  external   = 0, // null pointers, integers, 32-bit floats, etc.
  local_min  = 1,
  local_max  = 7,
  global_min = 8,
  global_max = 15
};

enum triggers : int {
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
struct gc_ptr;

class gc {

  gc(gc const &);               // private copy constructor for RAII
  gc & operator = (gc const &); // private assignment operator for RAII

  // global garbage collector configuration
  std::uint32_t regions_begin;    // lo <= x < hi
  std::uint32_t regions_end;
  std::uint64_t * mapped_regions; // 1 bit per region, packed

  std::mutex mutex_;
  std::unordered_set<core*> cores;

  friend core;
  friend gc_ptr;

  void add_core(core & c) {
    std::unique_lock<std::mutex> lock(mutex_);
    cores.insert(&c);
  }
  void remove_core(core & c) {
    std::unique_lock<std::mutex> lock(mutex_);
    cores.erase(&c);
    // TODO: check to make sure we don't need to advance the gc state if we were blocking?
  }

  public:
    gc() {}  // TODO: allocate a growable system heap, add parameters here
    ~gc() {} // TODO: return memory to the system

    inline bool is_protected_region(uint32_t r) {
      assert(regions_begin <= r && r < regions_end);
      return mapped_regions[r>>6]&(1<<((r-regions_begin)&0x3f));
    }
};

// cores are haskell execution contexts.
class core {
  private:
    core(core const &);               // private copy constructor for RAII
    core & operator = (core const &); // private assignment operator for RAII

    std::uint16_t expected_nmt; // array of 16 bits
    gc & system_;
    // static extern boost::thread_specific_ptr<core> current;

  public:
    core(gc & system) : system_(system) {
      current = this;
      system_.add_core(*this);
    }
    ~core() {
      system_.remove_core(*this);
      current = nullptr;
    }

    static thread_local core * current;
    // perform raii to bind to the current_core

    inline bool get_expected_nmt(int i) { return expected_nmt & (1 << i); }
};

struct gc_ptr {

  union {
    // layout chosen so that a 0-extended 32 bit integer is a legal 'gc_ptr' as are legal native c pointers
    // TODO: consider setting space 15 to also be a native pointer that way 0 and 1 extended pointers would
    // be legal.
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
  static const std::uintptr_t mask = 0x7ffffffff8;

  template <typename T> T & operator * () {
    return *reinterpret_cast<T *>(addr & mask);
  };

  template <typename T> T * operator -> () {
    return reinterpret_cast<T *>(addr & mask);
  };

  // modify this to look like an assignment operator e.g. operator = (...) ?

  inline void lvb(void * address) {
    int trigger = 0;
    auto c = core::current;
    if (nmt != c->get_expected_nmt(space))          trigger |= triggers::nmt;
    if (space && c->gc.in_protected_region(region)) trigger |= triggers::reloc;
    if (trigger) lvb_slow_path(address, trigger)
  }

  private:
    void lvb_slow_path(void * address, int trigger);
};

#endif
