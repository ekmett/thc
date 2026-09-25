// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#define _DEFAULT_SOURCE 1
#define _DARWIN_C_SOURCE 1
#include <errno.h>
#include <limits.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>

/* Measure libc's caller-owned image operations, never sigprocmask or a
 * process/thread signal mask. Reject a layout outside this exact byte model. */
int main(void) {
  sigset_t set;
  unsigned char *bytes = (unsigned char *)&set;
  unsigned char cleared[sizeof(set)];
  int bits[NSIG];
  if (CHAR_BIT != 8 || sizeof(void *) != 8 || sizeof(int) != 4 ||
      sizeof(set) > 4096 || NSIG < 2 || NSIG > 129) return 1;
  memset(&set, 0xa5, sizeof(set));
  errno = 123;
  if (sigemptyset(&set) != 0 || errno != 123) return 2;
  for (size_t i = 0; i < sizeof(set); ++i) {
    if (bytes[i] != 0 && bytes[i] != 0xa5) return 3;
    cleared[i] = bytes[i] == 0;
  }
  memset(&set, 0x5a, sizeof(set));
  if (sigemptyset(&set) != 0 || errno != 123) return 4;
  for (size_t i = 0; i < sizeof(set); ++i)
    if (bytes[i] != (cleared[i] ? 0 : 0x5a)) return 5;
  for (int signal = 1; signal < NSIG; ++signal) {
    memset(&set, 0, sizeof(set));
    errno = 123;
    const int status = sigaddset(&set, signal);
    if (status == -1 && errno == EINVAL) {
      // Some libc versions reject reserved signal numbers inside NSIG.
      for (size_t i = 0; i < sizeof(set); ++i) if (bytes[i] != 0) return 14;
      memset(&set, 0xa5, sizeof(set)); errno = 123;
      if (sigaddset(&set, signal) != -1 || errno != EINVAL) return 15;
      for (size_t i = 0; i < sizeof(set); ++i) if (bytes[i] != 0xa5) return 16;
      bits[signal] = -1;
      continue;
    }
    if (status != 0 || errno != 123) {
      fprintf(stderr, "sigaddset(%d): status=%d errno=%d NSIG=%d\n", signal, status, errno, NSIG);
      return 6;
    }
    int bit = -1;
    for (size_t i = 0; i < sizeof(set); ++i) for (int j = 0; j < 8; ++j)
      if (bytes[i] & (1u << j)) {
        if (bit != -1 || !cleared[i]) return 7;
        bit = (int)i * 8 + j;
      }
    if (bit < 0) return 8;
    bits[signal] = bit;
    for (int earlier = 1; earlier < signal; ++earlier)
      if (bits[earlier] == bit) return 9;
    for (int fill = 0x5a; fill <= 0xa5; fill += 0x4b) {
      memset(&set, fill, sizeof(set));
      if (sigaddset(&set, signal) != 0 || errno != 123 ||
          sigaddset(&set, signal) != 0 || errno != 123) return 10;
      for (size_t i = 0; i < sizeof(set); ++i)
        if (bytes[i] != (fill | (i == (size_t)bit / 8 ? 1u << (bit % 8) : 0))) return 11;
    }
  }
  const int invalid[] = {INT_MIN, -1, 0, NSIG, NSIG + 1, INT_MAX};
  for (size_t n = 0; n < sizeof(invalid) / sizeof(invalid[0]); ++n) {
    memset(&set, 0xa5, sizeof(set));
    errno = 123;
    if (sigaddset(&set, invalid[n]) != -1 || errno != EINVAL) return 12;
    for (size_t i = 0; i < sizeof(set); ++i) if (bytes[i] != 0xa5) return 13;
  }
  printf("{\"size\":%zu,\"alignment\":%zu,\"invalidErrno\":%d,\"clearBytes\":[",
         sizeof(set), _Alignof(sigset_t), EINVAL);
  int comma = 0;
  for (size_t i = 0; i < sizeof(set); ++i) if (cleared[i]) {
    printf("%s%zu", comma ? "," : "", i); comma = 1;
  }
  printf("],\"signalBits\":[");
  for (int signal = 1; signal < NSIG; ++signal)
    printf("%s%d", signal == 1 ? "" : ",", bits[signal]);
  puts("]}");
  return 0;
}
