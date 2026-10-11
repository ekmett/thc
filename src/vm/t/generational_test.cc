// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include "jam_vm.h"
#include "reservation.h"
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <cstring>
#include <vector>
#include <atomic>
#include <thread>

static constexpr uint32_t young_bit = 0x80000000u;
static void check(bool value, char const * message) {
  if (!value) { std::fprintf(stderr, "FAIL: %s\n", message); std::abort(); }
}
struct object { uint64_t header; uint32_t left, right; uint64_t identity; };
struct heap {
  size_t page = test::page_size();
  size_t bytes = page * 8;
  size_t span = (1ull << 34) + page + bytes;
  void * base = test::reserve(span);
  jam_vm * vm;
  explicit heap(size_t workers) {
    check(base != nullptr, "sparse canonical reservation");
    vm = jam_vm_create(base, page, bytes, bytes, page * 2, workers);
  }
  ~heap() { jam_vm_destroy(vm); check(test::release(base, span), "release reservation"); }
  object & at(uint32_t o) { return *reinterpret_cast<object *>(static_cast<char *>(base) + 8ull * o); }
  uint32_t make(bool young, uint64_t id, uint32_t left = 0, uint32_t right = 0) {
    auto o = jam_vm_allocate(vm, 3, young);
    check(o && bool(o & young_bit) == young, "allocation generation encoding");
    at(o) = {0x8000000100000800ull, left, right, id};
    return o;
  }
  static void scan(void * ctx, jam_vm_visit * v, uint32_t o) {
    auto & h = *static_cast<heap *>(ctx);
    check(jam_vm_thread_current(h.vm), "tracer binds the owning Jam heap");
    if (!jam_vm_claim(v, o, 3)) return;
    if (jam_vm_tracks_starts(h.vm)) jam_vm_record_start(h.vm, o);
    check(h.at(o).header == 0x8000000100000800ull, "opaque JVM header intact");
    uint64_t fields[] = {uint64_t(o) * 2 + 2, uint64_t(o) * 2 + 3};
    jam_vm_fields(v, fields, 2, 1);
    jam_vm_remember(h.vm, o, fields[0], 2, 1, 1);
  }
  void trace(uint32_t o) { jam_vm_trace(vm, &o, 1, scan, this, 1); }
  void weak_close() { jam_vm_weak_close(vm, scan, this); jam_vm_weak_finalizers(vm, scan, this); }
};
static void candidate_owners() {
  heap h(4);
  h.make(true, 999); // Compaction must repair the registry, not preserve raw offsets.
  auto a = h.make(true, 1), b = h.make(true, 2), live = h.make(true, 3);
  h.at(a).left = b; h.at(b).left = a;
  auto ta = jam_vm_candidate_arm(h.vm, a, 11);
  auto tb = jam_vm_candidate_arm(h.vm, b, 12);
  auto tc = jam_vm_candidate_arm(h.vm, live, 13);
  auto w = jam_vm_weak_create(h.vm, a, b, 0);
  check(ta && tb && tc, "ABI registers candidates");
  jam_vm_begin(h.vm, 1);
  jam_vm_weak_roots(h.vm, heap::scan, &h); h.weak_close();
  check(jam_vm_marked(h.vm, a) && jam_vm_marked(h.vm, b), "minor roots armed owners");
  check(jam_vm_prepare(h.vm, 0), "candidate minor fits");
  live = jam_vm_forward(h.vm, live); jam_vm_finish(h.vm);
  check(!jam_vm_candidate_epoch(h.vm) && !jam_vm_candidate_poll(h.vm, ta, 11), "minor never selects");
  jam_vm_begin(h.vm, 0);
  jam_vm_weak_roots(h.vm, heap::scan, &h); h.trace(live); h.weak_close();
  check(jam_vm_weak_value(h.vm, w), "rescue precedes generalized weak retirement");
  check(!jam_vm_candidate_epoch(h.vm), "no publication before movement");
  check(jam_vm_prepare(h.vm, 1), "candidate major fits");
  jam_vm_finish(h.vm);
  check(jam_vm_candidate_epoch(h.vm) == 1, "finish publishes the selected batch");
  a = jam_vm_candidate_poll(h.vm, ta, 11); b = jam_vm_candidate_poll(h.vm, tb, 12);
  check(a && b && h.at(a).identity == 1 && h.at(b).identity == 2 &&
        h.at(a).left == b && h.at(b).left == a, "batch rescue and relocation preserve cycle");
  check(!jam_vm_candidate_poll(h.vm, tc, 13), "ordinarily rooted candidate stays armed");
  live = jam_vm_candidate_disarm(h.vm, tc, 13);
  check(live && h.at(live).identity == 3, "normal wake claims relocated armed owner");
  jam_vm_candidate_complete(h.vm, tc, 13);
  jam_vm_candidate_complete(h.vm, ta, 99);
  jam_vm_begin(h.vm, 0); jam_vm_weak_roots(h.vm, heap::scan, &h); h.weak_close();
  check(jam_vm_marked(h.vm, a) && jam_vm_marked(h.vm, b) && !jam_vm_marked(h.vm, live),
        "claimed owners survive later GC until exact completion");
  check(jam_vm_prepare(h.vm, 0), "claimed candidate collection fits"); jam_vm_finish(h.vm);
  check(!jam_vm_candidate_poll(h.vm, ta, 11) && !jam_vm_candidate_disarm(h.vm, tb, 12), "no duplicate native claim");
  jam_vm_candidate_complete(h.vm, ta, 11); jam_vm_candidate_complete(h.vm, tb, 12);
  jam_vm_begin(h.vm, 0); jam_vm_weak_roots(h.vm, heap::scan, &h); h.weak_close();
  check(!jam_vm_weak_value(h.vm, w), "completed candidate cycle is no longer retained");
  check(jam_vm_prepare(h.vm, 0), "completed candidate reclaim fits"); jam_vm_finish(h.vm);
  check(jam_vm_used(h.vm, 0) == h.page / 8 && jam_vm_used(h.vm, 1) == h.page / 8,
        "all released candidate payloads are reclaimed");
}
static void object_starts() {
  heap h(1);
  size_t count = 123;
  check(jam_vm_start_bits(h.vm, 0, &count) == nullptr && count == 0, "starts disabled by default");
  auto first = h.make(false, 1), second = h.make(true, 2);
  h.at(first).left = second;
  check(jam_vm_track_starts(h.vm) && jam_vm_tracks_starts(h.vm), "enable start tracking");
  jam_vm_begin(h.vm, 0); h.trace(first);
  check(jam_vm_prepare(h.vm, 0), "tracked ABI major fits");
  first = jam_vm_forward(h.vm, first); second = jam_vm_forward(h.vm, second);
  jam_vm_finish(h.vm);
  for (auto at : {first, second}) {
    auto bits = jam_vm_start_bits(h.vm, bool(at & young_bit), &count);
    auto cell = at & ~young_bit;
    check(bits && cell / 32 < count && (bits[cell / 32] & (1u << (cell % 32))), "ABI start follows relocation");
  }
}
struct remembered_test {
  struct entry { uint32_t holder; uint64_t slot; bool compressed; uint64_t base_slot; };
  std::vector<entry> entries;
  static void append(void * context, uint32_t holder, uint64_t slot, int compressed, uint64_t base_slot) {
    static_cast<remembered_test *>(context)->entries.push_back({holder, slot, compressed != 0, base_slot});
  }
  void read(jam_vm * vm) { entries.clear(); jam_vm_remembered(vm, append, this); }
};
static void exact_remembered_slots() {
  heap h(4);
  h.make(false, 999); // Leave room for the remembered holder to move on a major.
  auto old = h.make(false, 1), live = h.make(true, 2), weak = h.make(true, 3);
  h.at(old).left = live; h.at(old).right = weak;
  uint64_t first = uint64_t(old) * 2 + 2;
  std::vector<std::thread> writers;
  for (unsigned i = 0; i != 4; ++i)
    writers.emplace_back([&] { for (unsigned j = 0; j != 100; ++j) jam_vm_remember(h.vm, old, first, 2, 1, 1); });
  for (auto & writer : writers) writer.join();
  jam_vm_begin(h.vm, 1);
  remembered_test recorded;
  recorded.read(h.vm);
  check(recorded.entries.size() == 2, "bulk/concurrent barriers deduplicate exact slots");
  for (auto entry : recorded.entries) {
    check(entry.holder == old && entry.compressed, "remembered holder and width retained");
    if (entry.slot == first) h.trace(h.at(old).left);
    else check(entry.slot == first + 1, "adjacent fields retain separate obligations");
  }
  check(jam_vm_marked(h.vm, live) && !jam_vm_marked(h.vm, weak), "remembering does not strengthen weak slots");
  h.at(old).right = 0; // VM reference policy, before prepare/repair.
  check(jam_vm_prepare(h.vm, 0), "remembered retaining minor fits");
  live = jam_vm_forward(h.vm, live);
  h.at(old).left = live;
  jam_vm_finish(h.vm);
  jam_vm_begin(h.vm, 1); recorded.read(h.vm);
  check(recorded.entries.size() == 1 && recorded.entries[0].slot == first, "cleared slots pruned; surviving edge retained");
  h.trace(live);
  check(jam_vm_prepare(h.vm, 0), "second remembered minor fits");
  live = jam_vm_forward(h.vm, live); h.at(old).left = live;
  jam_vm_finish(h.vm);
  jam_vm_begin(h.vm, 0); h.trace(old);
  check(jam_vm_prepare(h.vm, 0), "remembered major fits");
  old = jam_vm_forward(h.vm, old); live = jam_vm_forward(h.vm, live);
  jam_vm_finish(h.vm);
  check(uint64_t(old) * 2 + 2 < first && h.at(old).left == live, "major moves remembered source and target");
  jam_vm_begin(h.vm, 1); recorded.read(h.vm);
  check(recorded.entries.size() == 1 && recorded.entries[0].holder == old &&
        recorded.entries[0].slot == uint64_t(old) * 2 + 2, "remembered source relocates without a heap walk");
  h.trace(live);
  check(jam_vm_prepare(h.vm, 1), "remembered promotion fits");
  h.at(old).left = jam_vm_forward(h.vm, live); jam_vm_finish(h.vm);
  jam_vm_begin(h.vm, 1); recorded.read(h.vm);
  check(recorded.entries.empty(), "promotion empties old-to-young set");
  check(jam_vm_prepare(h.vm, 0), "empty young fits"); jam_vm_finish(h.vm);
  jam_vm_begin(h.vm, 0); recorded.read(h.vm);
  check(recorded.entries.empty(), "major drops stale holders before marking");
  check(jam_vm_prepare(h.vm, 0), "dead remembered holder reclaimed"); jam_vm_finish(h.vm);
}
static void retired_remembered_slots() {
  heap h(1);
  auto old = h.make(false, 1), live = h.make(true, 2), retired = h.make(true, 3);
  h.at(old).left = live; h.at(old).right = retired;
  auto first = uint64_t(old) * 2 + 2;
  jam_vm_remember(h.vm, 0, first, 2, 1, 1); // Raw strong slots, as in arraycopy.
  jam_vm_forget(h.vm, first + 1, 1);
  jam_vm_begin(h.vm, 1);
  remembered_test snapshot; snapshot.read(h.vm);
  check(snapshot.entries.size() == 1 && snapshot.entries[0].holder == 0 &&
        snapshot.entries[0].slot == first, "retirement removes only discarded raw slots");
  h.trace(h.at(old).left);
  check(!jam_vm_marked(h.vm, retired), "uncleared retired storage does not retain its target");
  check(jam_vm_prepare(h.vm, 0), "retired slot minor fits");
  h.at(old).left = jam_vm_forward(h.vm, live);
  jam_vm_finish(h.vm);
  jam_vm_forget(h.vm, first, 2);
  jam_vm_begin(h.vm, 1); snapshot.read(h.vm);
  check(snapshot.entries.empty(), "retirement is idempotent over absent slots");
  check(jam_vm_prepare(h.vm, 0), "fully retired minor fits"); jam_vm_finish(h.vm);
}
struct wide_remembered_heap {
  heap h{1};
  uint32_t owner = 0;
  uintptr_t address(uint32_t at) { return reinterpret_cast<uintptr_t>(h.base) + uint64_t(at) * 8; }
  void write(unsigned field, uintptr_t value) { std::memcpy(reinterpret_cast<void *>(address(owner) + field * 8), &value, sizeof(value)); }
  uintptr_t read(unsigned field) { uintptr_t value; std::memcpy(&value, reinterpret_cast<void *>(address(owner) + field * 8), sizeof(value)); return value; }
  static void scan(void * context, jam_vm_visit * visit, uint32_t at) {
    auto & self = *static_cast<wide_remembered_heap *>(context);
    if (at != self.owner) { heap::scan(&self.h, visit, at); return; }
    if (!jam_vm_claim(visit, at, 4)) return;
    auto slot = uint64_t(at) * 2;
    jam_vm_remember(self.h.vm, at, slot + 2, 1, 2, 0);
    jam_vm_remember_derived(self.h.vm, at, slot + 2, slot + 4, 0);
    auto target = uint32_t((self.read(1) - reinterpret_cast<uintptr_t>(self.h.base)) / 8);
    jam_vm_targets(visit, &target, 1);
  }
};
static void wide_and_derived_remembered_slots() {
  wide_remembered_heap x;
  auto & h = x.h;
  h.make(false, 999); h.make(true, 999);
  x.owner = jam_vm_allocate(h.vm, 4, 0);
  auto target = h.make(true, 23);
  x.write(0, 123); x.write(1, x.address(target)); x.write(2, x.address(target) + 13); x.write(3, 456);
  jam_vm_begin(h.vm, 0);
  jam_vm_trace(h.vm, &x.owner, 1, wide_remembered_heap::scan, &x, 1);
  check(jam_vm_prepare(h.vm, 0), "wide remembered major fits");
  target = jam_vm_forward(h.vm, target);
  x.write(1, x.address(target)); x.write(2, x.address(target) + 13);
  x.owner = jam_vm_forward(h.vm, x.owner);
  jam_vm_finish(h.vm);
  check(x.read(1) == x.address(target) && x.read(2) == x.address(target) + 13, "wide source and derived displacement survive major");
  for (unsigned round = 0; round != 2; ++round) {
    jam_vm_begin(h.vm, 1);
    remembered_test snapshot; snapshot.read(h.vm);
    check(snapshot.entries.size() == 2, "wide base and derived slots retained");
    for (auto e : snapshot.entries) {
      check(!e.compressed && e.holder == x.owner, "wide remembered holder/encoding preserved");
      if (e.base_slot) check(e.base_slot == uint64_t(x.owner) * 2 + 2 && e.slot == e.base_slot + 2, "derived source relationship relocates");
      else check(e.slot == uint64_t(x.owner) * 2 + 2, "wide base slot relocates");
    }
    h.trace(target);
    check(jam_vm_prepare(h.vm, round != 0), "wide minor/promotion fits");
    target = jam_vm_forward(h.vm, target);
    x.write(1, x.address(target)); x.write(2, x.address(target) + 13);
    jam_vm_finish(h.vm);
    check(x.read(1) == x.address(target) && x.read(2) == x.address(target) + 13, "wide/derived repair survives retaining minor and promotion");
  }
  jam_vm_begin(h.vm, 1);
  remembered_test snapshot; snapshot.read(h.vm);
  check(snapshot.entries.empty(), "promotion discards wide and derived remembered entries");
  check(jam_vm_prepare(h.vm, 0), "empty wide minor fits"); jam_vm_finish(h.vm);
}
static void cycles(size_t workers, bool require_simd) {
  heap h(workers);
  if (require_simd) check(std::strcmp(jam_vm_compactor(h.vm), "baseline") != 0, "SIMD selected");
  auto old = h.make(false, 1);
  // Every third cycle promotes; others rotate only young. Cross-generation edges
  // and both halves of compressed fields are rewritten by jam's existing kernel.
  for (unsigned n = 0; n != 40; ++n) {
    h.make(true, 999);
    auto young = h.make(true, n + 10, old);
    h.at(young).right = young;
    h.at(old).left = young;
    auto const old_origin = jam_vm_origin(h.vm, 0);
    auto const old_before = old;
    bool promote = n % 3 == 2;
    jam_vm_begin(h.vm, 1);
    h.trace(old); // Ordinary old roots must not cause a scan of the whole old graph.
    check(!jam_vm_marked(h.vm, young), "minor old root is not recursively traversed");
    jam_vm_trace_old(h.vm, &old, 1, heap::scan, &h);
    check(jam_vm_marked(h.vm, young), "remembered owner traces young");
    check(jam_vm_prepare(h.vm, promote), "minor prepare");
    old = jam_vm_forward(h.vm, old);
    young = jam_vm_forward(h.vm, young);
    h.at(old).left = young; // Host repairs old fields before movement.
    jam_vm_finish(h.vm);
    check(old == old_before && jam_vm_origin(h.vm, 0) == old_origin, "minor preserves old addresses and ring");
    check(bool(young & young_bit) != promote, "promotion changes generation bit exactly");
    check(h.at(young).identity == n + 10 && h.at(young).left == old && h.at(young).right == young,
          "young payload and cross-generation/self pointers");
    check(jam_vm_used(h.vm, 1) == h.page / 8 + (promote ? 0 : 3), "young guard retained but never promoted");
    // A major traces a mixed cycle and compacts both independently.
    jam_vm_begin(h.vm, 0);
    h.trace(old);
    check(jam_vm_prepare(h.vm, 0), "major prepare");
    old = jam_vm_forward(h.vm, old);
    young = jam_vm_forward(h.vm, young);
    jam_vm_finish(h.vm);
    check(h.at(old).left == young && h.at(young).left == old && h.at(young).right == young,
          "both forwarding tables survive both moves");
    h.at(old).left = 0;
  }
  std::printf("both generations: workers=%zu compactor=%s, 40 mixed cycles passed\n", workers, jam_vm_compactor(h.vm));
}
static void capacity_and_retry() {
  heap h(4);
  // Combined live bytes exceed either generation. Major must not promote all.
  size_t count = h.bytes / sizeof(object) * 3 / 4;
  uint32_t old = 0, young = 0;
  for (size_t n = 0; n != count; ++n) { old = h.make(false, n, old); young = h.make(true, n, young); }
  uint32_t roots[] = {old, young};
  jam_vm_begin(h.vm, 0);
  jam_vm_trace(h.vm, roots, 2, heap::scan, &h, 4);
  check(jam_vm_prepare(h.vm, 0), "major fits combined live data exceeding old capacity");
  old = jam_vm_forward(h.vm, old); young = jam_vm_forward(h.vm, young);
  jam_vm_finish(h.vm);
  auto id = jam_vm_weak_create(h.vm, old, young, 0);
  auto candidate = jam_vm_candidate_arm(h.vm, young, 41);
  jam_vm_begin(h.vm, 1);
  h.weak_close(); // Old key retains the complete young chain without a young root.
  check(!jam_vm_prepare(h.vm, 1), "promotion refuses insufficient old capacity");
  check(jam_vm_weak_value(h.vm, id) == young, "failed prepare does not forward weak registry");
  check(jam_vm_marked(h.vm, young), "failed prepare remains marking phase");
  check(jam_vm_candidate_disarm(h.vm, candidate, 41) == young,
        "failed prepare leaves candidate reference unchanged");
  check(jam_vm_prepare(h.vm, 0), "failed promotion retries retaining minor");
  young = jam_vm_forward(h.vm, young);
  jam_vm_finish(h.vm);
  check(jam_vm_weak_value(h.vm, id) == young && (young & young_bit), "retaining minor preserves young weak value");
  auto p = young;
  for (size_t n = count; n; --n) { check(h.at(p).identity == n - 1, "whole chain survived retry"); p = h.at(p).left; }
  check(!p, "chain terminates");
  jam_vm_candidate_complete(h.vm, candidate, 41);
  // A major can finally reject the unrooted old key; minor conservative liveness is scoped.
  jam_vm_begin(h.vm, 0); h.weak_close();
  check(!jam_vm_weak_value(h.vm, id), "old weak key dies on major");
  check(jam_vm_prepare(h.vm, 0), "empty major prepare"); jam_vm_finish(h.vm);
  check(jam_vm_used(h.vm, 0) == h.page / 8 && jam_vm_used(h.vm, 1) == h.page / 8, "both generations reclaimed");
}
static void mixed_weak_batch() {
  heap h(4);
  auto old_key = h.make(false, 1), young_key = h.make(true, 2);
  auto value = h.make(true, 3, young_key), finalizer = h.make(true, 4);
  auto w2 = jam_vm_weak_create(h.vm, young_key, finalizer, 0);
  auto w1 = jam_vm_weak_create(h.vm, old_key, value, 0);
  auto dead_key = h.make(true, 5), capture = h.make(true, 6, dead_key);
  auto w3 = jam_vm_weak_create(h.vm, dead_key, 0, capture);
  auto w4 = jam_vm_weak_create(h.vm, dead_key, finalizer, 0);
  jam_vm_begin(h.vm, 1); h.weak_close();
  check(jam_vm_weak_value(h.vm, w1) && jam_vm_weak_value(h.vm, w2), "mixed-generation weak fixed point");
  check(!jam_vm_weak_value(h.vm, w4) && jam_vm_marked(h.vm, dead_key), "retirement precedes captured key resurrection");
  check(jam_vm_prepare(h.vm, 1), "weak promotion prepare"); jam_vm_finish(h.vm);
  check(!(jam_vm_weak_value(h.vm, w1) & young_bit), "weak values follow promotion");
  uint64_t id;
  auto f = jam_vm_weak_take(h.vm, &id);
  check(id == w3 && f && !(f & young_bit), "pending finalizer follows promotion");
  jam_vm_begin(h.vm, 0); jam_vm_weak_roots(h.vm, heap::scan, &h); h.weak_close();
  check(jam_vm_marked(h.vm, f), "running promoted finalizer is a major root");
  check(jam_vm_prepare(h.vm, 0), "nested major prepare"); jam_vm_finish(h.vm);
  jam_vm_weak_complete(h.vm, id);
  check(!jam_vm_weak_finalize(h.vm, id), "completed finalizer cannot run twice");
}
static void permanent_references() {
  heap h(4);
  // Permanent image objects need no accessible backing for collector metadata.
  // Visiting either as a Jam object would fault, even when used as a weak key.
  uint32_t image = 0x10000000u;
  jam_vm_add_immortal_range(h.vm, image, uint64_t(image) + 8);
  auto value = h.make(true, 17, image, image + 1);
  auto weak = jam_vm_weak_create(h.vm, image, value, image + 2);
  auto dead = jam_vm_weak_create(h.vm, h.make(true, 18), image + 3, image + 4);
  for (unsigned n = 0; n != 6; ++n) {
    jam_vm_begin(h.vm, n % 2);
    h.trace(image);
    h.weak_close();
    check(jam_vm_marked(h.vm, image), "image key is permanently live");
    check(jam_vm_marked(h.vm, value), "image key retains managed value");
    check(jam_vm_prepare(h.vm, 1), "image reference prepare");
    value = jam_vm_forward(h.vm, value);
    check(jam_vm_forward(h.vm, image) == image, "image root preserves identity");
    jam_vm_finish(h.vm);
    check(h.at(value).left == image && h.at(value).right == image + 1,
          "SIMD leaves permanent fields intact");
    check(jam_vm_weak_value(h.vm, weak) == value, "image weak value follows movement");
  }
  uint64_t id;
  check(jam_vm_weak_take(h.vm, &id) == image + 4 && id == dead,
        "permanent finalizer survives managed key death");
  jam_vm_weak_complete(h.vm, id);
  check(jam_vm_weak_finalize(h.vm, weak) == image + 2, "explicit permanent finalizer");
  jam_vm_weak_complete(h.vm, weak);
}
static void concurrent_old_pin() {
  heap h(4);
  auto pinned = h.make(false, 0);
  auto * payload = &h.at(pinned).identity;
  std::atomic<bool> stop{false};
  std::atomic<uint64_t> writes{0};
  std::thread native([&] {
    uint64_t n = 0;
    while (!stop.load(std::memory_order_relaxed)) {
      __atomic_store_n(payload, ++n, __ATOMIC_RELAXED);
      writes.store(n, std::memory_order_release);
    }
  });
  while (!writes.load(std::memory_order_acquire)) std::this_thread::yield();
  for (unsigned n = 0; n != 200; ++n) {
    h.make(true, 999);
    auto young = h.make(true, n);
    jam_vm_begin(h.vm, 1);
    h.trace(young);
    check(jam_vm_prepare(h.vm, n % 2), "minor with concurrent native writer");
    jam_vm_finish(h.vm);
  }
  stop.store(true, std::memory_order_relaxed);
  native.join();
  check(__atomic_load_n(payload, __ATOMIC_RELAXED) == writes.load(std::memory_order_acquire),
        "old backing retains concurrent native writes across retaining/promoting minors");
}
static void concurrent_alias_pin() {
  heap h(4);
  check(jam_vm_allocate(h.vm, h.page / 4, 1), "dead prefix allocation");
  auto pinned = h.make(true, 0);
  auto * pin = jam_vm_pin_object(h.vm, pinned, 3);
  auto * const alias = static_cast<object *>(jam_vm_pin_address(pin));
  auto * const payload = &alias->identity;
  std::atomic<bool> stop{false};
  std::atomic<uint64_t> writes{0};
  std::thread native([&] {
    uint64_t n = 0;
    while (!stop.load(std::memory_order_relaxed)) {
      __atomic_store_n(payload, ++n, __ATOMIC_RELAXED);
      writes.store(n, std::memory_order_release);
    }
  });
  while (!writes.load(std::memory_order_acquire)) std::this_thread::yield();
  for (unsigned n = 0; n != 24; ++n) {
    h.make(true, 999);
    jam_vm_begin(h.vm, n % 3 != 0);
    jam_vm_pin_roots(h.vm, heap::scan, &h);
    check(jam_vm_prepare(h.vm, n % 3 == 2), "pinned major/retaining/promoting preparation");
    pinned = jam_vm_forward(h.vm, pinned);
    jam_vm_finish(h.vm);
    check(jam_vm_pin_address(pin) == alias, "side alias survives canonical republication");
    check(h.at(pinned).header == alias->header, "published canonical pin header intact");
  }
  stop.store(true, std::memory_order_relaxed);
  native.join();
  check(__atomic_load_n(payload, __ATOMIC_RELAXED) == writes.load(std::memory_order_acquire),
        "pin keeps final native write across all collection modes");
  check(h.at(pinned).identity == writes.load(std::memory_order_acquire),
        "republished canonical mapping shares pinned backing");
  jam_vm_unpin(h.vm, pin);
}
static void thread_scopes() {
  heap first(2), second(2);
  auto * a = jam_vm_thread_create(first.vm);
  auto * b = jam_vm_thread_create(second.vm);
  auto * reentrant = jam_vm_thread_create(first.vm);
  check(!jam_vm_thread_current(first.vm) && !jam_vm_thread_current(second.vm), "creation does not bind the creator");
  jam_vm_thread_enter(a);
  jam_vm_thread_enter(a);
  check(jam_vm_thread_current(first.vm), "thread entry binds Jam's actual TLS");
  jam_vm_thread_enter(b);
  check(jam_vm_thread_current(second.vm) && !jam_vm_thread_current(first.vm), "nested heap entry");
  jam_vm_thread_enter(reentrant);
  check(jam_vm_thread_current(first.vm), "A to B to A heap reentry");
  jam_vm_thread_leave(reentrant);
  jam_vm_thread_destroy(reentrant);
  check(jam_vm_thread_current(second.vm), "reentry restores B");
  jam_vm_thread_leave(b);
  jam_vm_thread_leave(b);
  check(jam_vm_thread_current(first.vm), "leaving B restores A");
  auto target = second.make(true, 31);
  jam_vm_begin(second.vm, 0);
  second.trace(target);
  check(jam_vm_thread_current(first.vm), "collector callback restores its caller's heap");
  check(jam_vm_prepare(second.vm, 0), "scope callback collection fits");
  jam_vm_finish(second.vm);
  std::atomic<bool> bound{false}, release{false};
  std::thread worker([&] {
    jam_vm_thread_enter(b);
    check(jam_vm_thread_current(second.vm) && !jam_vm_thread_current(first.vm), "target thread has independent TLS");
    bound.store(true, std::memory_order_release);
    while (!release.load(std::memory_order_acquire)) std::this_thread::yield();
    jam_vm_thread_leave(b);
    check(!jam_vm_thread_current(second.vm), "target thread restores empty scope");
  });
  while (!bound.load(std::memory_order_acquire)) std::this_thread::yield();
  check(jam_vm_thread_current(first.vm), "worker binding leaves parent scope intact");
  release.store(true, std::memory_order_release);
  worker.join();
  jam_vm_thread_destroy(b); // Deferred reclamation on a different OS thread.
  jam_vm_thread_leave(a);
  jam_vm_thread_destroy(a);
  check(!jam_vm_thread_current(first.vm), "last scope restores empty TLS");
}
int main(int argc, char **) {
  candidate_owners();
  object_starts();
  exact_remembered_slots();
  retired_remembered_slots();
  wide_and_derived_remembered_slots();
  cycles(1, argc > 1); cycles(4, argc > 1); capacity_and_retry(); mixed_weak_batch();
  permanent_references(); concurrent_old_pin(); concurrent_alias_pin(); thread_scopes();
}
