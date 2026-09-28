// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
#include <math.h>
#include <unistd.h>
extern long archive_dependency_tick(void);
static long calls;
__attribute__((constructor)) static void initialize(void) { archive_dependency_tick(); }
HsDouble archive_provider_math(HsDouble value) {
  return erf(value) + archive_dependency_tick() - ++calls - 1;
}
HsInt archive_provider_process(HsInt ignored) { (void) ignored; return getpid(); }
