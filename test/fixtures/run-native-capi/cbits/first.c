/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include <stdint.h>
static uint64_t private_helper(uint64_t value) { return value + 11; }
uint64_t native_first(uint64_t value) { return private_helper(value); }
