// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <stdint.h>

// Called only under the owner's native lifetime borrow, after exact range and
// alignment checks. Never widen narrow CAS into a neighboring sentinel or tail.
uint64_t thc_atomic_cas_narrow(void *address, uint64_t width,
    uint64_t expected, uint64_t desired) {
  if (width == 1) {
    uint8_t old = (uint8_t) expected;
    __atomic_compare_exchange_n((uint8_t *) address, &old, (uint8_t) desired,
        0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
    return old;
  }
  uint16_t old = (uint16_t) expected;
  __atomic_compare_exchange_n((uint16_t *) address, &old, (uint16_t) desired,
      0, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
  return old;
}
