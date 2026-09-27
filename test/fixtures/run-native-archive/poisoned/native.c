// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
#include <unistd.h>
static volatile HsInt process;
__attribute__((constructor)) static void initialize(void) { process = getpid(); }
HsInt archive_poisoned(HsInt ignored) { (void) ignored; return process; }
