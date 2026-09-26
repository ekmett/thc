// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
#include <unistd.h>
HsInt archive_process(HsInt ignored) { (void) ignored; return getpid(); }
