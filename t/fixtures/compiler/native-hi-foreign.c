/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */
/* Input: an integer supplied by the shared GHC oracle.
 * Outputs: native object for the oracle; LLVM bitcode for package admission.
 * Purpose: observable function and static-data linkage through native .hi Core. */
#include <stdint.h>
int64_t native_hi_step(int64_t value) { return value * 3 + 5; }
const int64_t native_hi_constant = 29;
