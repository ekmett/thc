// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <stddef.h>
#include <stdint.h>
#include <unistd.h>

int entropy_write(unsigned char *buffer, size_t count) {
  return getentropy(buffer, count);
}

int entropy_null(size_t count) {
  return getentropy(NULL, count);
}

/* Deterministic status and bounds observations, not entropy-bit expectations. */
int entropy_guard(size_t count) {
  unsigned char buffer[258];
  for (size_t i = 0; i < sizeof(buffer); ++i) buffer[i] = 0xa5;
  int status = getentropy(buffer + 1, count);
  int intact = buffer[0] == 0xa5;
  size_t first = status == 0 ? count + 1 : 1;
  for (size_t i = first; i < sizeof(buffer); ++i) intact &= buffer[i] == 0xa5;
  return (status + 1) * 2 + intact;
}

#ifdef THC_NATIVE_ORACLE
#include <stdio.h>
int main(void) {
  const size_t counts[] = {0, 1, 8, 255, 256, 257, SIZE_MAX};
  for (size_t i = 0; i < sizeof(counts) / sizeof(counts[0]); ++i)
    printf("guard\t%zu\t%d\n", counts[i], entropy_guard(counts[i]));
  for (size_t i = 0; i < 2; ++i)
    printf("null\t%zu\t%d\n", i, entropy_null(i));
  return 0;
}
#endif
