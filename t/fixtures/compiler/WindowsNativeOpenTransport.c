// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
// Minimal Sulong transport for the genuine native package wrapper. The native
// producer checks HsBase.h/mode_t; this translation unit uses no SDK emulation.
_Static_assert(sizeof(int) == 4 && sizeof(short) == 2 && sizeof(void *) == 8, "Win64 scalar transport");
extern int fixture_original_open(unsigned short *, int, unsigned short);
int thc_windows_fixture_open(unsigned short *path, int flags, unsigned short mode) {
    return fixture_original_open(path, flags, mode);
}
