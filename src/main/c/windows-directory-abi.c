// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <windows.h>
#include <stddef.h>
#include <stdio.h>

int main(void) {
    _Static_assert(sizeof(void *) == 8, "Windows directory ABI requires 64-bit pointers");
    _Static_assert(sizeof(WCHAR) == 2 && sizeof(BOOL) == 4 && sizeof(DWORD) == 4,
                   "Unexpected Windows scalar ABI");
    printf("{\"pointerBytes\":%zu,\"wcharBytes\":%zu,\"boolBytes\":%zu,"
           "\"dwordBytes\":%zu,\"findDataBytes\":%zu,\"findDataAlignment\":%zu,"
           "\"nameOffset\":%zu,\"nameUnits\":%zu,\"noMoreFiles\":%lu}\n",
           sizeof(void *), sizeof(WCHAR), sizeof(BOOL), sizeof(DWORD),
           sizeof(WIN32_FIND_DATAW), _Alignof(WIN32_FIND_DATAW),
           offsetof(WIN32_FIND_DATAW, cFileName),
           sizeof(((WIN32_FIND_DATAW *)0)->cFileName) / sizeof(WCHAR),
           (unsigned long)ERROR_NO_MORE_FILES);
    return 0;
}
