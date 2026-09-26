// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
#include <unistd.h>
#include <fcntl.h>
HsInt archive_process(HsInt ignored) { (void) ignored; return getpid(); }
HsInt32 archive_descriptor(void) { return open("/dev/null", O_RDONLY); }
