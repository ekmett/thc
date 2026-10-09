// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <HsBase.h>

// The installed package owns both the inline wrapper and its fs.c body.
// Link the original archive, not a fixture implementation of __hscore_open.
_Static_assert(sizeof(int) == 4 && sizeof(mode_t) == 2 && sizeof(wchar_t) == 2 && sizeof(void *) == 8,
               "Pinned GHC Win64 opening ABI");
__declspec(dllexport) int fixture_original_open(wchar_t *path, int flags, unsigned short mode) {
    return __hscore_open(path, flags, mode);
}
