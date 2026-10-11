// SPDX-FileCopyrightText: 2026 Edward Kmett <ekmett@gmail.com>
// SPDX-License-Identifier: BSD-2-Clause OR Apache-2.0

#pragma once
#include <stddef.h>
#include <stdint.h>
/// \brief Export the opaque C ABI when building the collector, import it in VM clients.
#if defined(JAM_VM_STATIC)
#define JAM_VM_API
#elif defined(_WIN32)
#if defined(JAM_VM_BUILD)
#define JAM_VM_API __declspec(dllexport)
#else
#define JAM_VM_API __declspec(dllimport)
#endif
#elif defined(__GNUC__)
#define JAM_VM_API __attribute__((visibility("default")))
#else
#define JAM_VM_API
#endif
#ifdef __cplusplus
extern "C" {
#endif

typedef struct jam_vm jam_vm;
typedef struct jam_vm_visit jam_vm_visit;
typedef struct jam_vm_thread jam_vm_thread;
typedef struct jam_vm_pin jam_vm_pin;
typedef void (*jam_vm_scan)(void *, jam_vm_visit *, uint32_t);

/* VM owns an inaccessible reservation (Windows: placeholders) through base+16GiB+prefix+young_bytes.
 * Old objects start at base+prefix; young objects at base+16GiB+prefix.
 * Both prefixes remain inaccessible. Sizes exclude guards and compaction reserve.
 * All sizes are native-page multiples; pointers use nonzero-base shift 3.
 * Heap operations require stopped mutators or the VM's allocation lock.
 * The reservation must outlive the adapter and must be unmapped by the VM. */
JAM_VM_API jam_vm * jam_vm_create(void * base, size_t prefix, size_t old_bytes,
                      size_t young_bytes, size_t reserve_bytes, size_t workers);
JAM_VM_API void jam_vm_destroy(jam_vm *);
/* Each VM thread owns an initially inactive descriptor. The heap outlives it.
 * Enter/leave construct/destroy Jam's heap_scope on the executing OS thread;
 * they nest in LIFO order and are idempotent while current/already inactive.
 * Suspend before switching isolates or publishing a SubstrateVM native state.
 * Destroy requires inactivity and may run on another thread after handoff. */
JAM_VM_API jam_vm_thread * jam_vm_thread_create(jam_vm *);
JAM_VM_API void jam_vm_thread_enter(jam_vm_thread *);
JAM_VM_API void jam_vm_thread_leave(jam_vm_thread *);
JAM_VM_API void jam_vm_thread_destroy(jam_vm_thread *);
JAM_VM_API int jam_vm_thread_current(jam_vm const *);
/* Register a permanent, immovable range of encoded references [first, limit).
 * Ranges must exclude both managed arenas and null, and outlive this heap.
 * Their outgoing managed edges are supplied separately as external VM roots.
 * Registration requires stopped mutators or the VM lock, outside collection. */
JAM_VM_API void jam_vm_add_immortal_range(jam_vm *, uint32_t first, uint64_t limit);
JAM_VM_API uint32_t jam_vm_allocate(jam_vm *, size_t words, int young); /* zero on insufficient space */
JAM_VM_API size_t jam_vm_used(jam_vm const *, int young); /* cells, including guard prefix */
JAM_VM_API size_t jam_vm_origin(jam_vm const *, int young); /* private ring origin */
JAM_VM_API char const * jam_vm_compactor(jam_vm const *);

/* Optional object enumeration: enable while idle, then run an instrumented major.
 * Allocation failure returns zero without enabling tracking. The VM declares one
 * start after successfully claiming/formatting each actual object, never padding.
 * Later allocations must register starts too. A raw allocation may be a TLAB and
 * is intentionally NOT registered by jam_vm_allocate.
 * start_bits borrows canonical bits up to used(), one uint32_t per 32 cells.
 * Disabled tracking returns null/count=0. Borrow expires on collection/destruction;
 * inspect only with stopped mutators, and reacquire after every collection. */
JAM_VM_API int jam_vm_track_starts(jam_vm *);
JAM_VM_API int jam_vm_tracks_starts(jam_vm const *);
JAM_VM_API void jam_vm_record_start(jam_vm *, uint32_t object);
JAM_VM_API uint32_t const * jam_vm_start_bits(jam_vm const *, int young, size_t * count);


/* Pin a complete moving object without collecting. Duplicate pins share a
 * registration; release each acquisition. Native payload access uses address(),
 * not the canonical Java address. Pin/unpin require the VM allocation lock.
 * Native writers may alter primitive payload, never unbarriered references. */
JAM_VM_API jam_vm_pin * jam_vm_pin_object(jam_vm *, uint32_t object, size_t words);
JAM_VM_API void * jam_vm_pin_address(jam_vm_pin const *);
JAM_VM_API void jam_vm_unpin(jam_vm *, jam_vm_pin *);
/* Trace open pins before weak processing. Ordinary minor rules still apply. */
JAM_VM_API void jam_vm_pin_roots(jam_vm *, jam_vm_scan, void * context);
/* After finish, format these padding ranges before walking the object stream.
 * Ranges are measured in cells and remain valid until the next begin. */
JAM_VM_API size_t jam_vm_gap_count(jam_vm const *);
JAM_VM_API uint32_t jam_vm_gap_at(jam_vm const *, size_t index);
JAM_VM_API size_t jam_vm_gap_words(jam_vm const *, size_t index);

/* begin -> any number of trace/liveness operations -> prepare -> root repairs
 * using forward -> finish. finish republishes the stable alias. VM root repair
 * must happen before finish (including code relocations and derived pointers). */
/* Exact old source slots; no target load occurs in the mutator barrier. first and
 * stride are four-byte indices from base. Keep the holder for VM reference policy;
 * recording a holder does not trace it. A zero holder is allowed only for known
 * strong slots; weak-policy slots must supply their holder. All mutators stop before begin.
 * Major marking must record surviving old fields again. The set is relocated and
 * pruned during prepare, before the VM repairs source/root slots. */
JAM_VM_API void jam_vm_remember(jam_vm *, uint32_t holder, uint64_t first,
                               size_t count, size_t stride, int compressed);
/* Retire a contiguous range of four-byte source locations, regardless of width.
 * Contents need not be cleared; the VM guarantees these slots no longer hold refs. */
JAM_VM_API void jam_vm_forget(jam_vm *, uint64_t first, size_t count);
/* Derived entries carry an ordinary base slot; zero base_slot in the callback
 * denotes an ordinary reference. Only the base is traced; preserve displacement
 * when repairing the derived value. Both source locations belong to holder. */
JAM_VM_API void jam_vm_remember_derived(jam_vm *, uint32_t holder, uint64_t base_slot,
                                       uint64_t slot, int compressed);
typedef void (*jam_vm_remembered_visit)(void * context, uint32_t holder,
                                      uint64_t slot, int compressed, uint64_t base_slot);
JAM_VM_API void jam_vm_remembered(jam_vm *, jam_vm_remembered_visit, void * context);

JAM_VM_API void jam_vm_begin(jam_vm *, int minor);
JAM_VM_API void jam_vm_trace(jam_vm *, uint32_t const * roots, size_t count,
                  jam_vm_scan, void * context, size_t marker_workers);
/* Minor-only, serial scan of distinct dirty old owners. Ordinary trace skips old
 * roots during a minor. The VM deduplicates owners before calling trace_old. */
JAM_VM_API void jam_vm_trace_old(jam_vm *, uint32_t const * owners, size_t count,
                      jam_vm_scan, void * context);
JAM_VM_API int jam_vm_marked(jam_vm const *, uint32_t);
/* A failed promotion leaves the marking phase intact; retry with promote=0.
 * Major collection preserves both generations and ignores promote. */
JAM_VM_API int jam_vm_prepare(jam_vm *, int promote);
JAM_VM_API uint32_t jam_vm_forward(jam_vm const *, uint32_t);
JAM_VM_API void jam_vm_finish(jam_vm *);

/* Generalized weak associations are VM side metadata, not strong JNI handles.
 * Dropping the guest token does not cancel finalization. A finalizer is itself
 * a managed object; only its actual outgoing edges can retain the key.
 * IDs are stable and never reused. All mutator operations require the VM lock.
 * close reaches the least live-key fixed point, then retires the whole dead
 * batch before finalizers are traced. Java weak clearing occurs between close
 * and weak_finalizers; Java phantom processing follows weak_finalizers. */
/* Returns a positive token, or zero on metadata allocation/token exhaustion.
 * Failure leaves existing associations unchanged; no C++ exception escapes. */
JAM_VM_API uint64_t jam_vm_weak_create(jam_vm *, uint32_t key, uint32_t value, uint32_t finalizer);
JAM_VM_API uint32_t jam_vm_weak_value(jam_vm const *, uint64_t id);
JAM_VM_API void jam_vm_weak_roots(jam_vm *, jam_vm_scan, void * context);
JAM_VM_API void jam_vm_weak_close(jam_vm *, jam_vm_scan, void * context);
JAM_VM_API void jam_vm_weak_finalizers(jam_vm *, jam_vm_scan, void * context);
/* take transitions queued -> running; complete releases that finalizer root.
 * Explicit finalize also retires an active entry and transitions to running.
 * Finalizers run in the language runtime, after the collector safepoint. */
JAM_VM_API uint32_t jam_vm_weak_take(jam_vm *, uint64_t * id);
JAM_VM_API uint32_t jam_vm_weak_finalize(jam_vm *, uint64_t id);
JAM_VM_API void jam_vm_weak_complete(jam_vm *, uint64_t id);

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
JAM_VM_API uint64_t jam_vm_candidate_arm(jam_vm *, uint32_t owner, uint64_t wait);
JAM_VM_API uint32_t jam_vm_candidate_poll(jam_vm *, uint64_t ticket, uint64_t wait);
JAM_VM_API uint32_t jam_vm_candidate_disarm(jam_vm *, uint64_t ticket, uint64_t wait);
JAM_VM_API void jam_vm_candidate_complete(jam_vm *, uint64_t ticket, uint64_t wait);
/** Acquire the epoch published after heap repair. Snapshot before scanning;
 * scan initially and on changes, or always if saturated at INT64_MAX. */
JAM_VM_API uint64_t jam_vm_candidate_epoch(jam_vm const *);


/* Scan callback claims the entire object before declaring fields. Slot indices
 * are absolute four-byte offsets from the compressed-oop base. Batch fields to
 * avoid a foreign call per field. Weak fields are declared with follow=0 and
 * processed by VM policy before prepare. No callbacks may unwind across C ABI. */
JAM_VM_API int jam_vm_claim(jam_vm_visit *, uint32_t object, size_t words);
JAM_VM_API void jam_vm_targets(jam_vm_visit *, uint32_t const * targets, size_t count);
JAM_VM_API void jam_vm_fields(jam_vm_visit *, uint64_t const * slots, size_t count, int follow);

#ifdef __cplusplus
}
#endif
