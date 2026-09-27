// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
#include <math.h>
#include <unistd.h>
HsDouble archive_provider_math(HsDouble value) { return erf(value); }
HsInt archive_provider_process(HsInt ignored) { (void) ignored; return getpid(); }
