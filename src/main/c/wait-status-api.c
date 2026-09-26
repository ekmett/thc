// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

// The same installed header used by unix-2.8.8.0's original CAPI wrappers.
// In particular WCOREDUMP returns its raw mask, not a normalized Boolean.
#include "HsUnix.h"
_Static_assert(sizeof(int) == 4, "original unix wait status requires CInt32");

int thc_wait_WCOREDUMP(int status) { return WCOREDUMP(status); }
int thc_wait_WSTOPSIG(int status) { return WSTOPSIG(status); }
int thc_wait_WIFSTOPPED(int status) { return WIFSTOPPED(status); }
int thc_wait_WTERMSIG(int status) { return WTERMSIG(status); }
int thc_wait_WIFSIGNALED(int status) { return WIFSIGNALED(status); }
int thc_wait_WEXITSTATUS(int status) { return WEXITSTATUS(status); }
int thc_wait_WIFEXITED(int status) { return WIFEXITED(status); }
