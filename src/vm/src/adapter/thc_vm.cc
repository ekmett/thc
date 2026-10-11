// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include "thc_vm.h"
#include <cstdlib>
#include <new>
#include <span>
import thc.jam;

// This is the entire foreign interface. C++26 types, ownership and exceptions
// stay behind opaque C handles; HotSpot consumes only thc_vm.h as C++14.
struct thc_vm { thc::hosted_heap heap; };
struct thc_vm_thread {
  thc::hosted_heap::thread_scope scope;
  explicit thc_vm_thread(thc::hosted_heap & heap) noexcept : scope(heap) {}
};
struct thc_vm_visit { thc::hosted_heap::visitor & visitor; };
namespace {
auto scanner(thc_vm_scan scan, void * context) noexcept {
  return [=](thc::hosted_heap::visitor & visitor, uint32_t at) noexcept {
    thc_vm_visit visit{visitor};
    scan(context, &visit, at);
  };
}
auto tracer(thc_vm * vm, thc_vm_scan scan, void * context) noexcept {
  return [=](std::span<uint32_t const> roots) noexcept {
    vm->heap.trace(roots, scanner(scan, context));
  };
}
}
extern "C" {
thc_vm * thc_vm_create(void * base, size_t prefix, size_t old_bytes, size_t young_bytes,
                       size_t reserve, size_t workers) {
  auto * result = new (std::nothrow) thc_vm{{base, prefix, old_bytes, young_bytes, reserve, workers}};
  if (!result) std::abort();
  return result;
}
void thc_vm_destroy(thc_vm * vm) { delete vm; }
thc_vm_thread * thc_vm_thread_create(thc_vm * vm) {
  auto * result = new (std::nothrow) thc_vm_thread(vm->heap);
  if (!result) std::abort();
  return result;
}
void thc_vm_thread_enter(thc_vm_thread * thread) { thread->scope.enter(); }
void thc_vm_thread_leave(thc_vm_thread * thread) { thread->scope.leave(); }
void thc_vm_thread_destroy(thc_vm_thread * thread) { delete thread; }
int thc_vm_thread_current(thc_vm const * vm) { return vm->heap.current(); }
void thc_vm_add_immortal_range(thc_vm * vm, uint32_t first, uint64_t limit) {
  vm->heap.add_immortal_range(first, limit);
}
uint32_t thc_vm_allocate(thc_vm * vm, size_t words, int young) { return vm->heap.allocate(words, young); }
size_t thc_vm_used(thc_vm const * vm, int young) { return vm->heap.used(young); }
size_t thc_vm_origin(thc_vm const * vm, int young) { return vm->heap.origin(young); }
char const * thc_vm_compactor(thc_vm const * vm) { return vm->heap.compactor(); }
int thc_vm_track_starts(thc_vm * vm) { return vm->heap.track_starts(); }
int thc_vm_tracks_starts(thc_vm const * vm) { return vm->heap.tracks_starts(); }
void thc_vm_record_start(thc_vm * vm, uint32_t at) { vm->heap.record_start(at); }
uint32_t const * thc_vm_start_bits(thc_vm const * vm, int young, size_t * count) {
  auto bits = vm->heap.starts(young);
  *count = bits.size();
  return bits.data();
}

thc_vm_pin * thc_vm_pin_object(thc_vm * vm, uint32_t at, size_t words) {
  return reinterpret_cast<thc_vm_pin *>(vm->heap.pin_object(at, words));
}
void * thc_vm_pin_address(thc_vm_pin const * pin) {
  return reinterpret_cast<thc::hosted_heap::pin const *>(pin)->address();
}
void thc_vm_unpin(thc_vm * vm, thc_vm_pin * pin) {
  vm->heap.unpin(reinterpret_cast<thc::hosted_heap::pin *>(pin));
}
void thc_vm_pin_roots(thc_vm * vm, thc_vm_scan scan, void * context) {
  vm->heap.trace_pins(scanner(scan, context));
}
size_t thc_vm_gap_count(thc_vm const * vm) { return vm->heap.gaps().size(); }
uint32_t thc_vm_gap_at(thc_vm const * vm, size_t index) { return vm->heap.gaps()[index].at; }
size_t thc_vm_gap_words(thc_vm const * vm, size_t index) { return vm->heap.gaps()[index].words; }
void thc_vm_remember(thc_vm * vm, uint32_t holder, uint64_t first,
                     size_t count, size_t stride, int compressed) {
  vm->heap.remember(holder, first, count, stride, compressed);
}
void thc_vm_forget(thc_vm * vm, uint64_t first, size_t count) {
  vm->heap.forget(first, count);
}
void thc_vm_remember_derived(thc_vm * vm, uint32_t holder, uint64_t base_slot,
                             uint64_t slot, int compressed) {
  vm->heap.remember_derived(holder, base_slot, slot, compressed);
}
void thc_vm_remembered(thc_vm * vm, thc_vm_remembered_visit visit, void * context) {
  for (auto const & entry : vm->heap.remembered_slots())
    visit(context, entry.holder, entry.slot, entry.compressed, entry.base_slot);
}
void thc_vm_begin(thc_vm * vm, int minor) { vm->heap.begin(minor); }
void thc_vm_trace(thc_vm * vm, uint32_t const * roots, size_t count,
                  thc_vm_scan scan, void * context, size_t workers) {
  vm->heap.trace(std::span{roots, count}, scanner(scan, context), workers);
}
void thc_vm_trace_old(thc_vm * vm, uint32_t const * roots, size_t count,
                      thc_vm_scan scan, void * context) {
  vm->heap.trace(std::span{roots, count}, scanner(scan, context), 1, true);
}
int thc_vm_marked(thc_vm const * vm, uint32_t at) { return vm->heap.marked(at); }
int thc_vm_prepare(thc_vm * vm, int promote) { return vm->heap.prepare(promote); }
uint32_t thc_vm_forward(thc_vm const * vm, uint32_t at) { return vm->heap.forward(at); }
void thc_vm_finish(thc_vm * vm) { vm->heap.finish(); }
uint64_t thc_vm_weak_create(thc_vm * vm, uint32_t key, uint32_t value, uint32_t finalizer) {
  return vm->heap.weaks().create(key, value, finalizer);
}
uint32_t thc_vm_weak_value(thc_vm const * vm, uint64_t id) { return vm->heap.weaks().value(id); }
void thc_vm_weak_roots(thc_vm * vm, thc_vm_scan scan, void * context) {
  vm->heap.weak_roots(tracer(vm, scan, context));
}
void thc_vm_weak_close(thc_vm * vm, thc_vm_scan scan, void * context) {
  vm->heap.weak_close(tracer(vm, scan, context));
}
void thc_vm_weak_finalizers(thc_vm * vm, thc_vm_scan scan, void * context) {
  vm->heap.weaks().roots(tracer(vm, scan, context));
}
uint32_t thc_vm_weak_take(thc_vm * vm, uint64_t * id) { return vm->heap.weaks().take(*id); }
uint32_t thc_vm_weak_finalize(thc_vm * vm, uint64_t id) { return vm->heap.weaks().finalize(id); }
void thc_vm_weak_complete(thc_vm * vm, uint64_t id) { vm->heap.weaks().complete(id); }
uint64_t thc_vm_candidate_arm(thc_vm * vm, uint32_t owner, uint64_t wait) {
  return vm->heap.candidates().arm(owner, wait);
}
uint32_t thc_vm_candidate_poll(thc_vm * vm, uint64_t ticket, uint64_t wait) {
  return vm->heap.candidates().poll(ticket, wait);
}
uint32_t thc_vm_candidate_disarm(thc_vm * vm, uint64_t ticket, uint64_t wait) {
  return vm->heap.candidates().disarm(ticket, wait);
}
void thc_vm_candidate_complete(thc_vm * vm, uint64_t ticket, uint64_t wait) {
  vm->heap.candidates().complete(ticket, wait);
}
uint64_t thc_vm_candidate_epoch(thc_vm const * vm) { return vm->heap.candidates().epoch(); }
int thc_vm_claim(thc_vm_visit * visit, uint32_t at, size_t words) { return visit->visitor.claim(at, words); }
void thc_vm_targets(thc_vm_visit * visit, uint32_t const * targets, size_t count) {
  visit->visitor.targets(std::span{targets, count});
}
void thc_vm_fields(thc_vm_visit * visit, uint64_t const * slots, size_t count, int follow) {
  visit->visitor.fields(std::span{slots, count}, follow);
}
}
