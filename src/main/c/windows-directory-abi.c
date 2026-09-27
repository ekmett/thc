// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <windows.h>
#include <stddef.h>
#include <stdio.h>
#include <errno.h>

int main(void) {
    _Static_assert(sizeof(void *) == 8, "Windows directory ABI requires 64-bit pointers");
    _Static_assert(sizeof(WCHAR) == 2 && sizeof(BOOL) == 4 && sizeof(DWORD) == 4,
                   "Unexpected Windows scalar ABI");
    printf("{\"pointerBytes\":%zu,\"wcharBytes\":%zu,\"boolBytes\":%zu,"
           "\"dwordBytes\":%zu,\"findDataBytes\":%zu,\"findDataAlignment\":%zu,"
           "\"nameOffset\":%zu,\"nameUnits\":%zu,\"noMoreFiles\":%lu,",
           sizeof(void *), sizeof(WCHAR), sizeof(BOOL), sizeof(DWORD),
           sizeof(WIN32_FIND_DATAW), _Alignof(WIN32_FIND_DATAW),
           offsetof(WIN32_FIND_DATAW, cFileName),
           sizeof(((WIN32_FIND_DATAW *)0)->cFileName) / sizeof(WCHAR),
           (unsigned long)ERROR_NO_MORE_FILES);
    printf("\"cpInfoBytes\":%zu,\"cpInfoAlignment\":%zu,\"cpInfoFieldBytes\":%zu,"
           "\"cpInfoMaxOffset\":%zu,\"cpInfoDefaultOffset\":%zu,\"cpInfoLeadOffset\":%zu,"
           "\"formatMessageFlags\":%lu,\"defaultLanguage\":%u,",
           sizeof(CPINFO), _Alignof(CPINFO), offsetof(CPINFO, LeadByte) + MAX_LEADBYTES,
           offsetof(CPINFO, MaxCharSize), offsetof(CPINFO, DefaultChar), offsetof(CPINFO, LeadByte),
           (unsigned long)(FORMAT_MESSAGE_FROM_SYSTEM | FORMAT_MESSAGE_ALLOCATE_BUFFER),
           (unsigned)MAKELANGID(LANG_NEUTRAL, SUBLANG_DEFAULT));
    printf("\"errno\":{\"EINVAL\":%d,\"ENOENT\":%d,\"EMFILE\":%d,\"EACCES\":%d,"
           "\"EBADF\":%d,\"ENOMEM\":%d,\"E2BIG\":%d,\"ENOEXEC\":%d,\"EXDEV\":%d,"
           "\"EEXIST\":%d,\"EAGAIN\":%d,\"EPIPE\":%d,\"ENOSPC\":%d,\"ECHILD\":%d,\"ENOTEMPTY\":%d}}\n",
           EINVAL, ENOENT, EMFILE, EACCES, EBADF, ENOMEM, E2BIG, ENOEXEC, EXDEV,
           EEXIST, EAGAIN, EPIPE, ENOSPC, ECHILD, ENOTEMPTY);
    return 0;
}
