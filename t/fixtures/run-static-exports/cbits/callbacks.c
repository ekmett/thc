/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include "callbacks.h"
#include <stdlib.h>

extern HsInt32 thc_pkg_callback(HsStablePtr pointer, HsInt32 value);
static HsInt32 (*entry)(HsStablePtr, HsInt32);
__attribute__((constructor)) static void initialize(void) {
  entry = thc_pkg_callback;
}
static const char *volatile digits = "1";
int native_adjust(int value) { return value + (int)strtol(digits, NULL, 10); }
int native_roundtrip(HsStablePtr pointer, int value) {
  int result = entry(pointer, value);
  hs_free_stable_ptr(pointer);
  return result;
}
