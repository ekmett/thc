/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include <stdint.h>

uintptr_t variant_sum(const unsigned char *bytes, uintptr_t length) {
  uintptr_t result = 0;
  for (uintptr_t i = 0; i < length; ++i) result += bytes[i];
  return result;
}
