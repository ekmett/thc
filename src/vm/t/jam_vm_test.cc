// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include "jam_vm.h"
#include "reservation.h"
#include <cstdio>
#include <cstdlib>
#include <cstdint>
#include <vector>
#include <cstring>

static bool require_simd = false;
static void check(bool value, char const * message) {
  if (!value) { std::fprintf(stderr, "FAIL: %s\n", message); std::abort(); }
}
// Opaque mark/class words are deliberately pointer-shaped: only VM oop-map
// fields may be rewritten. Actual HotSpot layout is supplied by the VM scanner.
struct object { uint64_t header; uint32_t left, right; uint64_t identity; };
static object * at(void * base, uint32_t offset) {
  return reinterpret_cast<object *>(static_cast<char *>(base) + 8ull * offset);
}
static void scan(void * base, jam_vm_visit * visit, uint32_t offset) {
  if (!jam_vm_claim(visit, offset, sizeof(object) / 8)) return;
  uint64_t fields[] = {uint64_t(offset) * 2 + 2, uint64_t(offset) * 2 + 3};
  jam_vm_fields(visit, fields, 2, 1);
  check(at(base, offset)->header == 0x8000000100000800ull, "header retained during scan");
}
static uint32_t make(jam_vm * vm, void * base, uint64_t identity, uint32_t left = 0, uint32_t right = 0) {
  auto offset = jam_vm_allocate(vm, sizeof(object) / 8, 0);
  check(offset != 0, "allocation");
  *at(base, offset) = {0x8000000100000800ull, left, right, identity};
  return offset;
}
static void run(size_t workers) {
  size_t page = test::page_size(), bytes = page * 64, reserve = page * 4;
  void * base = test::reserve((1ull << 34) + page + bytes);
  check(base != nullptr, "VM reservation");
  jam_vm * vm = jam_vm_create(base, page, bytes, bytes, reserve, workers);
  std::printf("workers=%zu compactor=%s\n", workers, jam_vm_compactor(vm));
  if (require_simd) check(std::strcmp(jam_vm_compactor(vm), "baseline") != 0, "SIMD selected on this host");
  uint32_t root = 0;
  for (unsigned epoch = 0; epoch != 40; ++epoch) {
    // Sparse dead gaps, sharing, cycles, and live data across many ring pages.
    for (unsigned n = 0; n != 2000; ++n) {
      make(vm, base, 999999);
      root = make(vm, base, epoch * 2000 + n, root);
      at(base, root)->right = root;
    }
    jam_vm_begin(vm, 0);
    jam_vm_trace(vm, &root, 1, scan, base, workers);
    check(jam_vm_marked(vm, root), "root marked");
    check(jam_vm_prepare(vm, 0), "major preparation");
    root = jam_vm_forward(vm, root);
    size_t before = jam_vm_origin(vm, 0);
    jam_vm_finish(vm);
    check(before != jam_vm_origin(vm, 0), "jam ring rotated");
    uint32_t p = root;
    for (unsigned n = 2000; n != 0; --n) {
      object const value = *at(base, p);
      check(value.header == 0x8000000100000800ull, "header unchanged by SIMD forwarding");
      check(value.identity == epoch * 2000 + n - 1, "survivor order and payload");
      check(value.right == p, "compressed self reference forwarded");
      p = value.left;
    }
    check(!p, "chain ends");
    check(jam_vm_used(vm, 0) == page / 8 + 2000 * 3, "dead gaps reclaimed exactly");
    root = 0; // Next cycle starts from a new graph; stale pointer masks must vanish.
  }
  // Separate drains support ephemeron closure without mutating or replaying GC.
  uint32_t key = make(vm, base, 1), next_key = make(vm, base, 2);
  uint32_t value = make(vm, base, 3, next_key), finalizer = make(vm, base, 4);
  jam_vm_begin(vm, 0);
  jam_vm_trace(vm, &key, 1, scan, base, 1);
  check(!jam_vm_marked(vm, next_key), "conditional edge initially absent");
  uint32_t activated[] = {value, finalizer};
  jam_vm_trace(vm, activated, 2, scan, base, 1);
  check(jam_vm_marked(vm, next_key), "conditional edge closes through jam frontier");
  check(jam_vm_prepare(vm, 0), "major preparation");
  key = jam_vm_forward(vm, key);
  value = jam_vm_forward(vm, value);
  next_key = jam_vm_forward(vm, next_key);
  jam_vm_finish(vm);
  check(at(base, key)->identity == 1 && at(base, value)->left == next_key, "repeated-drain movement");
  // VM allocation slowpath receives failure, not jam's capacity abort.
  check(!jam_vm_allocate(vm, bytes, 0), "out of space returns null");
  jam_vm_destroy(vm);
  check(test::release(base, (1ull << 34) + page + bytes), "VM releases canonical reservation");
}
static void generalized_weaks() {
  size_t page = test::page_size(), bytes = page * 16;
  void * base = test::reserve((1ull << 34) + page + bytes);
  check(base != nullptr, "weak heap reservation");
  jam_vm * vm = jam_vm_create(base, page, bytes, bytes, page * 2, 4);
  uint32_t k1 = make(vm, base, 1), k2 = make(vm, base, 2);
  uint32_t v1 = make(vm, base, 3, k2), v2 = make(vm, base, 4);
  // Register backwards: a single ordered weak pass would miss this activation.
  uint64_t w2 = jam_vm_weak_create(vm, k2, v2, 0);
  uint64_t w1 = jam_vm_weak_create(vm, k1, v1, 0);
  uint32_t dead_key = make(vm, base, 5);
  uint32_t dead_value = make(vm, base, 6, dead_key); // self-supporting V -> K is insufficient
  uint64_t dead = jam_vm_weak_create(vm, dead_key, dead_value, 0);
  uint32_t batch_key = make(vm, base, 7);
  uint32_t f1 = make(vm, base, 8, batch_key), f2 = make(vm, base, 9);
  uint64_t batch1 = jam_vm_weak_create(vm, batch_key, 0, f1);
  uint64_t batch2 = jam_vm_weak_create(vm, batch_key, 0, f2);
  jam_vm_begin(vm, 0);
  jam_vm_weak_roots(vm, scan, base);
  jam_vm_trace(vm, &k1, 1, scan, base, 1);
  jam_vm_weak_close(vm, scan, base);
  check(jam_vm_marked(vm, k2) && jam_vm_marked(vm, v2), "least ephemeron fixed point");
  check(!jam_vm_marked(vm, dead_key) && !jam_vm_marked(vm, dead_value), "no self-supported association");
  check(!jam_vm_weak_value(vm, dead), "dead value unavailable before finalizers");
  check(!jam_vm_marked(vm, batch_key), "dead batch frozen before finalizer graph");
  jam_vm_weak_finalizers(vm, scan, base);
  check(jam_vm_marked(vm, batch_key), "only actual finalizer captures retain keys");
  check(jam_vm_prepare(vm, 0), "major preparation");
  k1 = jam_vm_forward(vm, k1);
  jam_vm_finish(vm);
  check(at(base, jam_vm_weak_value(vm, w1))->identity == 3, "weak value forwarded");
  check(at(base, jam_vm_weak_value(vm, w2))->identity == 4, "fixed-point value forwarded");
  uint64_t id1, id2;
  uint32_t pending1 = jam_vm_weak_take(vm, &id1), pending2 = jam_vm_weak_take(vm, &id2);
  check((id1 == batch1 && id2 == batch2) || (id1 == batch2 && id2 == batch1),
        "same-key associations both retire in one batch without ordering guarantees");
  check(at(base, pending1)->identity == (id1 == batch1 ? 8 : 9) &&
        at(base, pending2)->identity == (id2 == batch1 ? 8 : 9), "finalizers forwarded");
  uint64_t none;
  check(!jam_vm_weak_take(vm, &none), "finalizers claimed at most once");
  jam_vm_begin(vm, 0);
  jam_vm_weak_roots(vm, scan, base);
  jam_vm_weak_close(vm, scan, base);
  jam_vm_weak_finalizers(vm, scan, base);
  check(jam_vm_marked(vm, pending1) && jam_vm_marked(vm, pending2), "running finalizers survive nested GC");
  check(jam_vm_prepare(vm, 0), "major preparation");
  jam_vm_finish(vm);
  check(!jam_vm_weak_finalize(vm, batch1), "running finalizer cannot run twice");
  jam_vm_weak_complete(vm, batch1);
  jam_vm_weak_complete(vm, batch2);
  jam_vm_begin(vm, 0);
  jam_vm_weak_roots(vm, scan, base);
  jam_vm_weak_close(vm, scan, base);
  jam_vm_weak_finalizers(vm, scan, base);
  check(jam_vm_prepare(vm, 0), "major preparation");
  jam_vm_finish(vm);
  check(jam_vm_used(vm, 0) == page / 8, "completed finalizers release captures and cycles");
  jam_vm_destroy(vm);
  check(test::release(base, (1ull << 34) + page + bytes), "release weak reservation");
}
int main(int argc, char **) { require_simd = argc > 1; run(1); run(4); generalized_weaks(); std::puts("actual jam adapter checks passed"); }
