/* SPDX-License-Identifier: MIT
 * SPDX-FileCopyrightText: 2025 rust-works
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * Copyright (c) 2025 rust-works; Copyright (c) 2026 Edward Kmett.
 * Adaptation of John Ky's succinctly JSON scanners.
 * See LICENSE.succinctly and README.md in this directory. */
#ifndef THC_JSON_INDEX_H
#define THC_JSON_INDEX_H
#include <stdint.h>
/* AUTO chooses a supported ISA. Explicit unavailable choices return UNSUPPORTED. */
enum thc_json_backend { THC_JSON_AUTO, THC_JSON_SCALAR, THC_JSON_AVX2, THC_JSON_NEON };
enum thc_json_status { THC_JSON_OK, THC_JSON_ARGUMENT, THC_JSON_UNSUPPORTED };
int thc_json_backend_available(int backend);
int thc_json_backend_selected(int backend);
/* Regenerate at most 512 bytes of Simple Cursor interest bits, carrying the
 * exact lexer state: 0=JSON, 1=string, 2=escaped next byte. All three masks use
 * one bit per input byte, padded to LE 64-bit words. Interest includes comma
 * and colon; open/close are disjoint subsets. Incomplete strings are valid
 * block boundaries, reported through final_state. No persistent bitmap is
 * required. mask_bytes must equal ceil(length/64)*8. Nonempty output buffers
 * must be disjoint from each other and the immutable source. */
int thc_json_scan_block(const uint8_t *source, uint64_t length, int backend,
                        uint32_t initial_state, uint32_t *final_state,
                        uint8_t *interest, uint8_t *opens, uint8_t *closes,
                        uint64_t mask_bytes);
#endif
