// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
static HsInt effects;
HsInt archive_allowed(HsInt value) { return value + 37; }
HsInt archive_count(HsInt ignored) { (void) ignored; return effects; }
HsInt archive_blocked(void *ignored) { (void) ignored; ++effects; return 99; }
HsInt archive_other(HsInt value) { return value + 4; }
