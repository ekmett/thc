// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#pragma once
#include <stddef.h>
#include <stdint.h>
/// \brief Export the opaque C ABI when building the collector, import it in VM clients.
#if defined(THC_VM_STATIC)
#define THC_VM_API
#elif defined(_WIN32)
#if defined(THC_VM_BUILD)
#define THC_VM_API __declspec(dllexport)
#else
#define THC_VM_API __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define THC_VM_API __attribute__((visibility("default")))
#else
#define THC_VM_API
#endif
#ifdef __cplusplus
extern "C" {
#endif

typedef struct thc_vm thc_vm;
typedef struct thc_vm_visit thc_vm_visit;
typedef struct thc_vm_thread thc_vm_thread;
typedef struct thc_vm_pin thc_vm_pin;
typedef void (*thc_vm_scan)(void *, thc_vm_visit *, uint32_t);

/* VM owns an inaccessible reservation (Windows: placeholders) through base+16GiB+prefix+young_bytes.
 * Old objects start at base+prefix; young objects at base+16GiB+prefix.
 * Both prefixes remain inaccessible. Sizes exclude guards and compaction reserve.
 * All sizes are native-page multiples; pointers use nonzero-base shift 3.
 * Heap operations require stopped mutators or the VM's allocation lock.
 * The reservation must outlive the adapter and must be unmapped by the VM. */
THC_VM_API thc_vm * thc_vm_create(void * base, size_t prefix, size_t old_bytes,
                      size_t young_bytes, size_t reserve_bytes, size_t workers);
THC_VM_API void thc_vm_destroy(thc_vm *);
/* Each VM thread owns an initially inactive descriptor. The heap outlives it.
 * Enter/leave construct/destroy Jam's heap_scope on the executing OS thread;
 * they nest in LIFO order and are idempotent while current/already inactive.
 * Suspend before switching isolates or publishing a SubstrateVM native state.
 * Destroy requires inactivity and may run on another thread after handoff. */
THC_VM_API thc_vm_thread * thc_vm_thread_create(thc_vm *);
THC_VM_API void thc_vm_thread_enter(thc_vm_thread *);
THC_VM_API void thc_vm_thread_leave(thc_vm_thread *);
THC_VM_API void thc_vm_thread_destroy(thc_vm_thread *);
THC_VM_API int thc_vm_thread_current(thc_vm const *);
/* Register a permanent, immovable range of encoded references [first, limit).
 * Ranges must exclude both managed arenas and null, and outlive this heap.
 * Their outgoing managed edges are supplied separately as external VM roots.
 * Registration requires stopped mutators or the VM lock, outside collection. */
THC_VM_API void thc_vm_add_immortal_range(thc_vm *, uint32_t first, uint64_t limit);
THC_VM_API uint32_t thc_vm_allocate(thc_vm *, size_t words, int young); /* zero on insufficient space */
THC_VM_API size_t thc_vm_used(thc_vm const *, int young); /* cells, including guard prefix */
THC_VM_API size_t thc_vm_origin(thc_vm const *, int young); /* private ring origin */
THC_VM_API char const * thc_vm_compactor(thc_vm const *);

/* Optional object enumeration: enable while idle, then run an instrumented major.
 * Allocation failure returns zero without enabling tracking. The VM declares one
 * start after successfully claiming/formatting each actual object, never padding.
 * Later allocations must register starts too. A raw allocation may be a TLAB and
 * is intentionally NOT registered by thc_vm_allocate.
 * start_bits borrows canonical bits up to used(), one uint32_t per 32 cells.
 * Disabled tracking returns null/count=0. Borrow expires on collection/destruction;
 * inspect only with stopped mutators, and reacquire after every collection. */
THC_VM_API int thc_vm_track_starts(thc_vm *);
THC_VM_API int thc_vm_tracks_starts(thc_vm const *);
THC_VM_API void thc_vm_record_start(thc_vm *, uint32_t object);
THC_VM_API uint32_t const * thc_vm_start_bits(thc_vm const *, int young, size_t * count);


/* Pin a complete moving object without collecting. Duplicate pins share a
 * registration; release each acquisition. Native payload access uses address(),
 * not the canonical Java address. Pin/unpin require the VM allocation lock.
 * Native writers may alter primitive payload, never unbarriered references. */
THC_VM_API thc_vm_pin * thc_vm_pin_object(thc_vm *, uint32_t object, size_t words);
THC_VM_API void * thc_vm_pin_address(thc_vm_pin const *);
THC_VM_API void thc_vm_unpin(thc_vm *, thc_vm_pin *);
/* Trace open pins before weak processing. Ordinary minor rules still apply. */
THC_VM_API void thc_vm_pin_roots(thc_vm *, thc_vm_scan, void * context);
/* After finish, format these padding ranges before walking the object stream.
 * Ranges are measured in cells and remain valid until the next begin. */
THC_VM_API size_t thc_vm_gap_count(thc_vm const *);
THC_VM_API uint32_t thc_vm_gap_at(thc_vm const *, size_t index);
THC_VM_API size_t thc_vm_gap_words(thc_vm const *, size_t index);

/* begin -> any number of trace/liveness operations -> prepare -> root repairs
 * using forward -> finish. finish republishes the stable alias. VM root repair
 * must happen before finish (including code relocations and derived pointers). */
/* Exact old source slots; no target load occurs in the mutator barrier. first and
 * stride are four-byte indices from base. Keep the holder for VM reference policy;
 * recording a holder does not trace it. A zero holder is allowed only for known
 * strong slots; weak-policy slots must supply their holder. All mutators stop before begin.
 * Major marking must record surviving old fields again. The set is relocated and
 * pruned during prepare, before the VM repairs source/root slots. */
THC_VM_API void thc_vm_remember(thc_vm *, uint32_t holder, uint64_t first,
                               size_t count, size_t stride, int compressed);
/* Retire a contiguous range of four-byte source locations, regardless of width.
 * Contents need not be cleared; the VM guarantees these slots no longer hold refs. */
THC_VM_API void thc_vm_forget(thc_vm *, uint64_t first, size_t count);
/* Derived entries carry an ordinary base slot; zero base_slot in the callback
 * denotes an ordinary reference. Only the base is traced; preserve displacement
 * when repairing the derived value. Both source locations belong to holder. */
THC_VM_API void thc_vm_remember_derived(thc_vm *, uint32_t holder, uint64_t base_slot,
                                       uint64_t slot, int compressed);
typedef void (*thc_vm_remembered_visit)(void * context, uint32_t holder,
                                      uint64_t slot, int compressed, uint64_t base_slot);
THC_VM_API void thc_vm_remembered(thc_vm *, thc_vm_remembered_visit, void * context);

THC_VM_API void thc_vm_begin(thc_vm *, int minor);
THC_VM_API void thc_vm_trace(thc_vm *, uint32_t const * roots, size_t count,
                  thc_vm_scan, void * context, size_t marker_workers);
/* Minor-only, serial scan of distinct dirty old owners. Ordinary trace skips old
 * roots during a minor. The VM deduplicates owners before calling trace_old. */
THC_VM_API void thc_vm_trace_old(thc_vm *, uint32_t const * owners, size_t count,
                      thc_vm_scan, void * context);
THC_VM_API int thc_vm_marked(thc_vm const *, uint32_t);
/* A failed promotion leaves the marking phase intact; retry with promote=0.
 * Major collection preserves both generations and ignores promote. */
THC_VM_API int thc_vm_prepare(thc_vm *, int promote);
THC_VM_API uint32_t thc_vm_forward(thc_vm const *, uint32_t);
THC_VM_API void thc_vm_finish(thc_vm *);

/* Generalized weak associations are VM side metadata, not strong JNI handles.
 * Dropping the guest token does not cancel finalization. A finalizer is itself
 * a managed object; only its actual outgoing edges can retain the key.
 * IDs are stable and never reused. All mutator operations require the VM lock.
 * close reaches the least live-key fixed point, then retires the whole dead
 * batch before finalizers are traced. Java weak clearing occurs between close
 * and weak_finalizers; Java phantom processing follows weak_finalizers. */
/* Returns a positive token, or zero on metadata allocation/token exhaustion.
 * Failure leaves existing associations unchanged; no C++ exception escapes. */
THC_VM_API uint64_t thc_vm_weak_create(thc_vm *, uint32_t key, uint32_t value, uint32_t finalizer);
THC_VM_API uint32_t thc_vm_weak_value(thc_vm const *, uint64_t id);
THC_VM_API void thc_vm_weak_roots(thc_vm *, thc_vm_scan, void * context);
THC_VM_API void thc_vm_weak_close(thc_vm *, thc_vm_scan, void * context);
THC_VM_API void thc_vm_weak_finalizers(thc_vm *, thc_vm_scan, void * context);
/* take transitions queued -> running; complete releases that finalizer root.
 * Explicit finalize also retires an active entry and transitions to running.
 * Finalizers run in the language runtime, after the collector safepoint. */
THC_VM_API uint32_t thc_vm_weak_take(thc_vm *, uint64_t * id);
THC_VM_API uint32_t thc_vm_weak_finalize(thc_vm *, uint64_t id);
THC_VM_API void thc_vm_weak_complete(thc_vm *, uint64_t id);

/** Optional owner rescue. Serialize with the host heap lock, except epoch().
 * arm requires a nonnull encoded owner and wait in 1..INT64_MAX; zero means
 * invalid input or metadata exhaustion, without changing prior registrations.
 * Minors retain all owners. Majors select unreachable armed owners after weak
 * closure, rescue the entire batch, then close/retire ordinary weak entries.
 * poll claims selected owners; disarm claims armed or selected owners. Both
 * return zero for stale tickets, mismatched waits or an existing claimant.
 * A claim stays rooted until complete, after ordinary ownership transfer or
 * terminal cancellation. Completion is idempotent and cannot cancel a rival.
 * Roots/closure/repair/publication use the existing weak_roots/weak_close and
 * prepare/finish phases. No Java callback runs during collection. */
THC_VM_API uint64_t thc_vm_candidate_arm(thc_vm *, uint32_t owner, uint64_t wait);
THC_VM_API uint32_t thc_vm_candidate_poll(thc_vm *, uint64_t ticket, uint64_t wait);
THC_VM_API uint32_t thc_vm_candidate_disarm(thc_vm *, uint64_t ticket, uint64_t wait);
THC_VM_API void thc_vm_candidate_complete(thc_vm *, uint64_t ticket, uint64_t wait);
/** Acquire the epoch published after heap repair. Snapshot before scanning;
 * scan initially and on changes, or always if saturated at INT64_MAX. */
THC_VM_API uint64_t thc_vm_candidate_epoch(thc_vm const *);


/* Scan callback claims the entire object before declaring fields. Slot indices
 * are absolute four-byte offsets from the compressed-oop base. Batch fields to
 * avoid a foreign call per field. Weak fields are declared with follow=0 and
 * processed by VM policy before prepare. No callbacks may unwind across C ABI. */
THC_VM_API int thc_vm_claim(thc_vm_visit *, uint32_t object, size_t words);
THC_VM_API void thc_vm_targets(thc_vm_visit *, uint32_t const * targets, size_t count);
THC_VM_API void thc_vm_fields(thc_vm_visit *, uint64_t const * slots, size_t count, int follow);

#ifdef __cplusplus
}
#endif
