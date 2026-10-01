/* SPDX-FileCopyrightText: 2026 Edward Kmett
 * SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause */

static unsigned long state;

__attribute__((constructor)) static void initialize(void) { state = 40; }
__attribute__((noinline)) long next(void) { return ++state; }
