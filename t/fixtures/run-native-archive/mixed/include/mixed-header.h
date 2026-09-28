/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#pragma once
#include <stdint.h>
typedef struct archive_state { uint64_t bias; } archive_state;
archive_state *archive_state_for(intptr_t ignored);
uint64_t archive_state_bias(archive_state *state);
uint64_t archive_header_mix(archive_state *state, uint8_t rounds, uint32_t keylen);
uint64_t archive_header_mix_wide(archive_state *state, uint8_t rounds, uint32_t keylen);
int64_t archive_header_mix16(archive_state *state, int16_t first, uint16_t second);
