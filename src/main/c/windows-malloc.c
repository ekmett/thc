// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <errno.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>

_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8 && sizeof(size_t) == 8 &&
               sizeof(int) == 4 && sizeof(long) == 4 && (size_t)-1 > 0,
               "Windows allocator requires the x86_64 LLP64 ABI");

// All allocation and release operations bind to this DLL's CRT. Capture errno
// before returning to the JVM: Win32 GetLastError is a different error channel.
__declspec(dllexport) void *thc_windows_malloc(size_t size, int *error) {
    void *result = malloc(size);
    *error = result == NULL ? errno : 0;
    return result;
}

__declspec(dllexport) void thc_windows_free(void *pointer) { free(pointer); }

// Checked again from the loaded DLL, not just from the build-time executable.
__declspec(dllexport) uint64_t thc_windows_malloc_abi(void) {
    return ((uint64_t)CHAR_BIT << 32) | ((uint64_t)sizeof(void *) << 24) |
           ((uint64_t)sizeof(size_t) << 16) | ((uint64_t)sizeof(int) << 8) | sizeof(long);
}

__declspec(dllexport) int thc_windows_malloc_enomem(void) { return ENOMEM; }

#ifdef THC_MALLOC_PROBE
#include <stdio.h>
#include <string.h>
#include <windows.h>

// MinGW's address of malloc/free can name an executable import thunk. Inspect
// the resolved PE import slots to report the actual allocator's CRT module.
extern void *(__cdecl *__imp_malloc)(size_t);
extern void (__cdecl *__imp_free)(void *);

static int module_name(const void *address, char *name) {
    HMODULE module;
    return GetModuleHandleExA(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS |
                             GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                             (LPCSTR)address, &module) && GetModuleFileNameA(module, name, MAX_PATH);
}

int main(void) {
    char allocation[MAX_PATH], release[MAX_PATH], errors[MAX_PATH];
    int error;
    void *pointer = thc_windows_malloc(32, &error);
    if (!pointer || error) return 1;
    ((unsigned char *)pointer)[31] = 197;
    if (((unsigned char *)pointer)[31] != 197) return 2;
    thc_windows_free(pointer);
    volatile size_t impossible = SIZE_MAX >> 1;
    pointer = thc_windows_malloc(impossible, &error);
    if (pointer || error != ENOMEM) {
        fprintf(stderr, "malloc(%llu)=%p errno=%d expected ENOMEM=%d\n",
                (unsigned long long)impossible, pointer, error, ENOMEM);
        if (pointer) thc_windows_free(pointer);
        return 3;
    }
    if (!module_name((const void *)__imp_malloc, allocation) ||
        !module_name((const void *)__imp_free, release) ||
        !module_name((const void *)_errno, errors)) return 4;
    // Emit basenames so the receipt is independent of the host's drive layout.
    printf("{\"abi\":%llu,\"enomem\":%d,\"mallocModule\":\"%s\",\"freeModule\":\"%s\",\"errnoModule\":\"%s\",\"failureErrno\":%d}\n",
           (unsigned long long)thc_windows_malloc_abi(), ENOMEM,
           strrchr(allocation, '\\') + 1, strrchr(release, '\\') + 1, strrchr(errors, '\\') + 1, error);
    return 0;
}
#endif
