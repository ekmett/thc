// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <stdint.h>
#include <iostream>

// Original simdutf also includes iostream even without using stream operators.
// Preserve its real libstdc++ Init and __cxa_atexit obligations.
static volatile int constructed;
struct witness {
  witness() { constructed = 42; }
  ~witness() { constructed = -1; }
};
static witness observed;
extern "C" intptr_t archive_lifecycle(intptr_t value) { return constructed + value; }
