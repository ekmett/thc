/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */

/* Native GHC compatibility only. THC recognizes these exact declarations
 * before dispatching package C calls. Do not fabricate JVM counters. */
long long thc_runtime_v1_query(int selector, long long index, long long detail) {
  (void)index;
  (void)detail;
  switch (selector) {
    case 0: /* native GHC runtime */
    case 1: /* native backend */
    case 100: /* native Haskell thread, not Java platform/virtual */
      return 0;
    default:
      return -1;
  }
}

long long thc_runtime_v1_control(int selector, long long setting) {
  (void)selector;
  (void)setting;
  return -1;
}

long long thc_runtime_v1_trace(int operation, long long token,
                              const unsigned char *bytes, long long length) {
  (void)operation;
  (void)token;
  (void)bytes;
  (void)length;
  return -1;
}
