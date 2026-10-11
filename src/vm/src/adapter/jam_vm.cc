// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#include "jam_vm.h"
#include <cstdlib>
#include <new>
#include <span>
import thc.jam;

// This is the entire foreign interface. C++26 types, ownership and exceptions
// stay behind opaque C handles; HotSpot consumes only jam_vm.h as C++14.
struct jam_vm { thc::hosted_heap heap; };
struct jam_vm_thread {
  thc::hosted_heap::thread_scope scope;
  explicit jam_vm_thread(thc::hosted_heap & heap) noexcept : scope(heap) {}
};
struct jam_vm_visit { thc::hosted_heap::visitor & visitor; };
namespace {
auto scanner(jam_vm_scan scan, void * context) noexcept {
  return [=](thc::hosted_heap::visitor & visitor, uint32_t at) noexcept {
    jam_vm_visit visit{visitor};
    scan(context, &visit, at);
  };
}
auto tracer(jam_vm * vm, jam_vm_scan scan, void * context) noexcept {
  return [=](std::span<uint32_t const> roots) noexcept {
    vm->heap.trace(roots, scanner(scan, context));
  };
}
}
extern "C" {
jam_vm * jam_vm_create(void * base, size_t prefix, size_t old_bytes, size_t young_bytes,
                       size_t reserve, size_t workers) {
  auto * result = new (std::nothrow) jam_vm{{base, prefix, old_bytes, young_bytes, reserve, workers}};
  if (!result) std::abort();
  return result;
}
void jam_vm_destroy(jam_vm * vm) { delete vm; }
jam_vm_thread * jam_vm_thread_create(jam_vm * vm) {
  auto * result = new (std::nothrow) jam_vm_thread(vm->heap);
  if (!result) std::abort();
  return result;
}
void jam_vm_thread_enter(jam_vm_thread * thread) { thread->scope.enter(); }
void jam_vm_thread_leave(jam_vm_thread * thread) { thread->scope.leave(); }
void jam_vm_thread_destroy(jam_vm_thread * thread) { delete thread; }
int jam_vm_thread_current(jam_vm const * vm) { return vm->heap.current(); }
void jam_vm_add_immortal_range(jam_vm * vm, uint32_t first, uint64_t limit) {
  vm->heap.add_immortal_range(first, limit);
}
uint32_t jam_vm_allocate(jam_vm * vm, size_t words, int young) { return vm->heap.allocate(words, young); }
size_t jam_vm_used(jam_vm const * vm, int young) { return vm->heap.used(young); }
size_t jam_vm_origin(jam_vm const * vm, int young) { return vm->heap.origin(young); }
char const * jam_vm_compactor(jam_vm const * vm) { return vm->heap.compactor(); }
int jam_vm_track_starts(jam_vm * vm) { return vm->heap.track_starts(); }
int jam_vm_tracks_starts(jam_vm const * vm) { return vm->heap.tracks_starts(); }
void jam_vm_record_start(jam_vm * vm, uint32_t at) { vm->heap.record_start(at); }
uint32_t const * jam_vm_start_bits(jam_vm const * vm, int young, size_t * count) {
  auto bits = vm->heap.starts(young);
  *count = bits.size();
  return bits.data();
}

jam_vm_pin * jam_vm_pin_object(jam_vm * vm, uint32_t at, size_t words) {
  return reinterpret_cast<jam_vm_pin *>(vm->heap.pin_object(at, words));
}
void * jam_vm_pin_address(jam_vm_pin const * pin) {
  return reinterpret_cast<thc::hosted_heap::pin const *>(pin)->address();
}
void jam_vm_unpin(jam_vm * vm, jam_vm_pin * pin) {
  vm->heap.unpin(reinterpret_cast<thc::hosted_heap::pin *>(pin));
}
void jam_vm_pin_roots(jam_vm * vm, jam_vm_scan scan, void * context) {
  vm->heap.trace_pins(scanner(scan, context));
}
size_t jam_vm_gap_count(jam_vm const * vm) { return vm->heap.gaps().size(); }
uint32_t jam_vm_gap_at(jam_vm const * vm, size_t index) { return vm->heap.gaps()[index].at; }
size_t jam_vm_gap_words(jam_vm const * vm, size_t index) { return vm->heap.gaps()[index].words; }
void jam_vm_remember(jam_vm * vm, uint32_t holder, uint64_t first,
                     size_t count, size_t stride, int compressed) {
  vm->heap.remember(holder, first, count, stride, compressed);
}
void jam_vm_forget(jam_vm * vm, uint64_t first, size_t count) {
  vm->heap.forget(first, count);
}
void jam_vm_remember_derived(jam_vm * vm, uint32_t holder, uint64_t base_slot,
                             uint64_t slot, int compressed) {
  vm->heap.remember_derived(holder, base_slot, slot, compressed);
}
void jam_vm_remembered(jam_vm * vm, jam_vm_remembered_visit visit, void * context) {
  for (auto const & entry : vm->heap.remembered_slots())
    visit(context, entry.holder, entry.slot, entry.compressed, entry.base_slot);
}
void jam_vm_begin(jam_vm * vm, int minor) { vm->heap.begin(minor); }
void jam_vm_trace(jam_vm * vm, uint32_t const * roots, size_t count,
                  jam_vm_scan scan, void * context, size_t workers) {
  vm->heap.trace(std::span{roots, count}, scanner(scan, context), workers);
}
void jam_vm_trace_old(jam_vm * vm, uint32_t const * roots, size_t count,
                      jam_vm_scan scan, void * context) {
  vm->heap.trace(std::span{roots, count}, scanner(scan, context), 1, true);
}
int jam_vm_marked(jam_vm const * vm, uint32_t at) { return vm->heap.marked(at); }
int jam_vm_prepare(jam_vm * vm, int promote) { return vm->heap.prepare(promote); }
uint32_t jam_vm_forward(jam_vm const * vm, uint32_t at) { return vm->heap.forward(at); }
void jam_vm_finish(jam_vm * vm) { vm->heap.finish(); }
uint64_t jam_vm_weak_create(jam_vm * vm, uint32_t key, uint32_t value, uint32_t finalizer) {
  return vm->heap.weaks().create(key, value, finalizer);
}
uint32_t jam_vm_weak_value(jam_vm const * vm, uint64_t id) { return vm->heap.weaks().value(id); }
void jam_vm_weak_roots(jam_vm * vm, jam_vm_scan scan, void * context) {
  vm->heap.weak_roots(tracer(vm, scan, context));
}
void jam_vm_weak_close(jam_vm * vm, jam_vm_scan scan, void * context) {
  vm->heap.weak_close(tracer(vm, scan, context));
}
void jam_vm_weak_finalizers(jam_vm * vm, jam_vm_scan scan, void * context) {
  vm->heap.weaks().roots(tracer(vm, scan, context));
}
uint32_t jam_vm_weak_take(jam_vm * vm, uint64_t * id) { return vm->heap.weaks().take(*id); }
uint32_t jam_vm_weak_finalize(jam_vm * vm, uint64_t id) { return vm->heap.weaks().finalize(id); }
void jam_vm_weak_complete(jam_vm * vm, uint64_t id) { vm->heap.weaks().complete(id); }
uint64_t jam_vm_candidate_arm(jam_vm * vm, uint32_t owner, uint64_t wait) {
  return vm->heap.candidates().arm(owner, wait);
}
uint32_t jam_vm_candidate_poll(jam_vm * vm, uint64_t ticket, uint64_t wait) {
  return vm->heap.candidates().poll(ticket, wait);
}
uint32_t jam_vm_candidate_disarm(jam_vm * vm, uint64_t ticket, uint64_t wait) {
  return vm->heap.candidates().disarm(ticket, wait);
}
void jam_vm_candidate_complete(jam_vm * vm, uint64_t ticket, uint64_t wait) {
  vm->heap.candidates().complete(ticket, wait);
}
uint64_t jam_vm_candidate_epoch(jam_vm const * vm) { return vm->heap.candidates().epoch(); }
int jam_vm_claim(jam_vm_visit * visit, uint32_t at, size_t words) { return visit->visitor.claim(at, words); }
void jam_vm_targets(jam_vm_visit * visit, uint32_t const * targets, size_t count) {
  visit->visitor.targets(std::span{targets, count});
}
void jam_vm_fields(jam_vm_visit * visit, uint64_t const * slots, size_t count, int follow) {
  visit->visitor.fields(std::span{slots, count}, follow);
}
}
