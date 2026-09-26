// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
#include "mixed-header.h"
static HsInt effects;
HsInt archive_allowed(HsInt value) { return value + 37; }
HsInt archive_count(HsInt ignored) { (void) ignored; return effects; }
HsInt archive_blocked(void *ignored) { (void) ignored; ++effects; return 99; }
HsInt archive_other(HsInt value) { return value + 4; }
HsInt64 archive_width(HsInt value) { ++effects; return value + 101; }
static archive_state state = { 7 };
archive_state *archive_state_for(intptr_t ignored) { (void) ignored; return &state; }
uint64_t archive_state_bias(archive_state *value) { return value->bias; }
uint64_t archive_header_mix(archive_state *value, uint8_t rounds, uint32_t keylen) {
  return value->bias + ((uint64_t) rounds << 32) + keylen;
}
uint64_t archive_header_mix_wide(archive_state *value, uint8_t rounds, uint32_t keylen) {
  return archive_header_mix(value, rounds, keylen);
}
