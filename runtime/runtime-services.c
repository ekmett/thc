/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */

/* Native GHC compatibility only. THC recognizes these exact declarations
 * before dispatching package C calls. Do not fabricate JVM counters. */
long long thc_runtime_v1_query(int selector, long long index, long long detail) {
  /* -5 is deliberately not an Availability status: the Haskell decoder turns
   * malformed private-ABI calls into diagnostics instead of invented values. */
  switch (selector) {
    case 0: /* native GHC runtime */
    case 1: /* native backend */
    case 100: /* native Haskell thread, not Java platform/virtual */
      return index == 0 && detail == 0 ? 0 : -5;
    case 5: case 6: case 7:
      return index == 0 && detail >= -1 ? -1 : -5;
    case 301:
      return index >= 0 && detail >= -1 ? -1 : -5;
    case 109: case 110: case 302: case 303:
      return index >= 0 && detail == 0 ? -1 : -5;
    case 2: case 3: case 4:
    case 101: case 102: case 103: case 104:
    case 105: case 106: case 107: case 108:
    case 200: case 201: case 202: case 203:
    case 204: case 205: case 206: case 207: case 208: case 209:
    case 300:
    case 400: case 401: case 402: case 403: case 404: case 405: case 406:
    case 500: case 501:
      return index == 0 && detail == 0 ? -1 : -5;
    default:
      return -5;
  }
}

long long thc_runtime_v1_control(int selector, long long setting) {
  return ((selector == 400 && setting >= 0 && setting <= 1) ||
          (selector == 500 && setting >= 0 && setting <= 3)) ? -1 : -5;
}

long long thc_runtime_v1_trace(int operation, long long token,
                              const unsigned char *bytes, long long length) {
  if (length < 0 || length > 1048576 || (length > 0 && bytes == 0)) return -5;
  if (operation == 0 || operation == 1) {
    if (token != 0) return -5;
  } else if (operation == 2 || operation == 3) {
    if (token <= 0 || length != 0) return -5;
  } else {
    return -5;
  }
  return -1;
}
