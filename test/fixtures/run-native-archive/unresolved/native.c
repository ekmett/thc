// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsFFI.h>
#include <unistd.h>
#include <fcntl.h>
HsInt archive_process(HsInt ignored) { (void) ignored; return getpid(); }
HsInt32 archive_descriptor(void) { return open("/dev/null", O_RDONLY); }
static HsInt partial_state;
__attribute__((constructor)) static void initialize_partial(void) { partial_state = 7; }
HsInt archive_partial_add(HsInt value) { partial_state += value; return partial_state; }
HsInt archive_partial_read(HsInt ignored) { (void) ignored; return partial_state; }
/* The dependency reaches getpid through address-taken global state, not a
   direct call instruction in this adapter. It must remain unavailable. */
static pid_t (*volatile process_pointer)(void) = getpid;
HsInt archive_through_global(HsInt ignored) { (void) ignored; return process_pointer(); }
