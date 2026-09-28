/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
#include "scalar.h"
int32_t thc_io_v1_close(int32_t x) { return x ^ SCALAR_DELTA; }
int64_t scalar_i64(int64_t x) { return x ^ SCALAR_DELTA; }
float scalar_float(float x) { return x * (float)SCALAR_DELTA; }
double scalar_double(double x) { return x * (double)SCALAR_DELTA; }
int64_t scalar_mixed(int32_t a, int64_t b, float c, double d) {
    return (int64_t)a + b + (int64_t)c + (int64_t)d + SCALAR_DELTA;
}
