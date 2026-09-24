// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <stdint.h>
#include <stdlib.h>

int64_t thc_add(int64_t a, int64_t b) { return a + b; }
unsigned char *thc_alloc(int64_t size) { return calloc((size_t)size, 1); }
void thc_store(unsigned char *p, int64_t offset, unsigned char value) { p[offset] = value; }
int64_t thc_load(const unsigned char *p, int64_t offset) { return p[offset]; }
unsigned char *thc_offset(unsigned char *p, int64_t offset) { return p + offset; }
void thc_release(unsigned char *p) { free(p); }

#ifdef THC_NATIVE_ORACLE
#include <inttypes.h>
#include <stdio.h>

int main(void) {
    for (int64_t row = 0; row < 8; ++row) {
        unsigned char *p = thc_alloc(24);
        if (p == NULL) return 1;
        thc_store(p, row, (unsigned char)(42 + row));
        thc_store(p, 16, (unsigned char)(254 - row));
        printf("%" PRId64 "\t%" PRId64 "\t%" PRId64 "\n",
            thc_add((INT64_C(1) << 40) + row, 42 - row),
            thc_load(thc_offset(p, row), 0), thc_load(p, 16));
        thc_release(p);
    }
    return 0;
}
#endif
