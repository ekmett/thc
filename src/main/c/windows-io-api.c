// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#define _WIN32_WINNT 0x0a00
#include <winsock2.h>
#include <windows.h>
#include <errno.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// This is the THC-owned RTS boundary, not a second implementation of libc.
// CRT calls bind to the selected package DLL's resolved imports. Never use
// the bridge's allocator CRT, the JVM's default lookup, or an assumed fd table.
typedef int *(__cdecl *errno_fn)(void);
typedef int (__cdecl *read_fn)(int, void *, unsigned);
typedef int (__cdecl *write_fn)(int, const void *, unsigned);
typedef int (__cdecl *dup_fn)(int);
typedef int (__cdecl *close_fn)(int);
typedef intptr_t (__cdecl *handle_fn)(int);
typedef int (WSAAPI *recv_fn)(SOCKET, char *, int, int);
typedef int (WSAAPI *send_fn)(SOCKET, const char *, int, int);
typedef int (WSAAPI *socket_error_fn)(void);
typedef int (WSAAPI *socket_close_fn)(SOCKET);
typedef BOOL (WINAPI *compare_fn)(HANDLE, HANDLE);
typedef _invalid_parameter_handler (__cdecl *local_validation_fn)(_invalid_parameter_handler);

struct io_owner {
    HMODULE package;
    errno_fn error;
    read_fn read;
    write_fn write;
    dup_fn duplicate;
    close_fn close;
    handle_fn handle;
    recv_fn recv;
    send_fn send;
    socket_error_fn socket_error;
    socket_close_fn socket_close;
    compare_fn compare;
    local_validation_fn local_validation;
};
struct io_loan { uintptr_t descriptor; int socket, standard_input; };
struct io_result { int length, error; DWORD windows_error; int console_abort; };

_Static_assert(sizeof(void *) == 8 && sizeof(int) == 4 && sizeof(DWORD) == 4 &&
               sizeof(struct io_loan) == 16 && sizeof(struct io_result) == 16,
               "Windows RTS IO requires the Win64 scalar ABI");

__declspec(dllexport) uint64_t thc_windows_io_abi(void) {
    return ((uint64_t)sizeof(void *) << 32) | ((uint64_t)sizeof(struct io_loan) << 16) | sizeof(struct io_result);
}

// The OS loader already validated this hash-qualified package image. Read only
// its resolved IAT, retaining the selected DLL for the entire binding lifetime.
static void *imported(HMODULE module, const char *name) {
    const unsigned char *base = (const unsigned char *)module;
    const IMAGE_DOS_HEADER *dos = (const IMAGE_DOS_HEADER *)base;
    const IMAGE_NT_HEADERS64 *nt = (const IMAGE_NT_HEADERS64 *)(base + dos->e_lfanew);
    DWORD offset = nt->OptionalHeader.DataDirectory[IMAGE_DIRECTORY_ENTRY_IMPORT].VirtualAddress;
    if (!offset) return NULL;
    const IMAGE_IMPORT_DESCRIPTOR *imports = (const IMAGE_IMPORT_DESCRIPTOR *)(base + offset);
    for (; imports->Name; ++imports) {
        if (!imports->OriginalFirstThunk) continue;
        const IMAGE_THUNK_DATA64 *names = (const IMAGE_THUNK_DATA64 *)(base + imports->OriginalFirstThunk);
        const IMAGE_THUNK_DATA64 *addresses = (const IMAGE_THUNK_DATA64 *)(base + imports->FirstThunk);
        for (; names->u1.AddressOfData; ++names, ++addresses) {
            if (IMAGE_SNAP_BY_ORDINAL64(names->u1.Ordinal)) continue;
            const IMAGE_IMPORT_BY_NAME *entry = (const IMAGE_IMPORT_BY_NAME *)(base + names->u1.AddressOfData);
            if (!strcmp((const char *)entry->Name, name)) return (void *)(uintptr_t)addresses->u1.Function;
        }
    }
    return NULL;
}

__declspec(dllexport) void *thc_windows_io_bind(const wchar_t *loaded_package, DWORD *error) {
    HMODULE package = NULL, crt = NULL;
    *error = 0;
    if (!GetModuleHandleExW(0, loaded_package, &package)) { *error = GetLastError(); return NULL; }
    struct io_owner *owner = calloc(1, sizeof(*owner));
    if (!owner) { FreeLibrary(package); *error = ERROR_NOT_ENOUGH_MEMORY; return NULL; }
    owner->package = package;
    owner->error = (errno_fn)imported(package, "_errno");
    owner->read = (read_fn)imported(package, "_read");
    owner->write = (write_fn)imported(package, "_write");
    owner->recv = (recv_fn)imported(package, "recv");
    owner->send = (send_fn)imported(package, "send");
    owner->socket_error = (socket_error_fn)imported(package, "WSAGetLastError");
    owner->socket_close = (socket_close_fn)imported(package, "closesocket");
    // GHC's bundled MinGW has the Win10 declaration but no import-library
    // entry. Resolve the OS operation from its existing fixed system module.
    owner->compare = (compare_fn)GetProcAddress(GetModuleHandleW(L"kernelbase.dll"), "CompareObjectHandles");
    if (!owner->error || !owner->read || !owner->write || !owner->recv || !owner->send ||
        !owner->socket_error || !owner->socket_close || !owner->compare ||
        !GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCWSTR)(uintptr_t)owner->error, &crt)) goto mismatch;
    // The package's CRT owner supplies acquisition and retirement too.
    owner->duplicate = (dup_fn)GetProcAddress(crt, "_dup");
    owner->close = (close_fn)GetProcAddress(crt, "_close");
    owner->handle = (handle_fn)GetProcAddress(crt, "_get_osfhandle");
    owner->local_validation = (local_validation_fn)GetProcAddress(crt, "_set_thread_local_invalid_parameter_handler");
    if (!owner->duplicate || !owner->close || !owner->handle || !owner->local_validation ||
        (void *)(uintptr_t)GetProcAddress(crt, "_read") != (void *)(uintptr_t)owner->read ||
        (void *)(uintptr_t)GetProcAddress(crt, "_write") != (void *)(uintptr_t)owner->write) goto mismatch;
    return owner;
mismatch:
    *error = ERROR_INVALID_DATA;
    FreeLibrary(package);
    free(owner);
    return NULL;
}

static _Thread_local int invalid_descriptor;
static void __cdecl recover_descriptor(const wchar_t *expression, const wchar_t *function,
                                       const wchar_t *file, unsigned line, uintptr_t reserved) {
    (void)expression; (void)function; (void)file; (void)line; (void)reserved;
    invalid_descriptor = 1;
}

// A closed fd is a recoverable guest IO error. UCRT's default validation
// handler terminates the JVM before _dup returns EBADF. Scope the documented
// thread-local callback to this one descriptor-only SDK call, and restore it
// on the same native origin before returning. Never alter the global handler
// or suppress validation of buffers, memory, other CRT calls or other threads.
static int duplicate(struct io_owner *owner, int fd, int *error) {
    int outer_invalid = invalid_descriptor;
    invalid_descriptor = 0;
    _invalid_parameter_handler previous = owner->local_validation(recover_descriptor);
    int result = owner->duplicate(fd);
    *error = result < 0 ? *owner->error() : 0;
    owner->local_validation(previous);
    int invalid = invalid_descriptor;
    invalid_descriptor = outer_invalid;
    if (invalid && (result != -1 || *error != EBADF)) RaiseFailFastException(NULL, NULL, 0);
    return result;
}

__declspec(dllexport) void thc_windows_io_unbind(struct io_owner *owner) {
    FreeLibrary(owner->package);
    free(owner);
}

__declspec(dllexport) int thc_windows_io_acquire(struct io_owner *owner, int fd, int socket,
                                              struct io_loan *loan) {
    loan->socket = socket != 0;
    loan->standard_input = 0;
    if (loan->socket) {
        WSAPROTOCOL_INFOW protocol;
        SOCKET original = (SOCKET)(uintptr_t)(uint32_t)fd;
        if (WSADuplicateSocketW(original, GetCurrentProcessId(), &protocol)) return WSAGetLastError();
        SOCKET copy = WSASocketW(FROM_PROTOCOL_INFO, FROM_PROTOCOL_INFO, FROM_PROTOCOL_INFO,
                                &protocol, 0, WSA_FLAG_OVERLAPPED);
        if (copy == INVALID_SOCKET) return WSAGetLastError();
        loan->descriptor = (uintptr_t)copy;
    } else {
        if (fd < 0) return EBADF;
        int error;
        int copy = duplicate(owner, fd, &error);
        if (copy < 0) return error;
        // Do not query a possibly closed original after duplication. The CRT
        // owns the copy, and CompareObjectHandles tests the actual kernel object.
        intptr_t handle = owner->handle(copy);
        HANDLE input = GetStdHandle(STD_INPUT_HANDLE);
        loan->standard_input = handle != -1 && input && input != INVALID_HANDLE_VALUE &&
                               owner->compare((HANDLE)handle, input);
        loan->descriptor = (uintptr_t)copy;
    }
    return 0;
}

__declspec(dllexport) void thc_windows_io_transfer(struct io_owner *owner, const struct io_loan *loan,
                                                 int writing, unsigned count, void *buffer,
                                                 struct io_result *result) {
    if (loan->socket) {
        result->length = writing ? owner->send((SOCKET)loan->descriptor, buffer, (int)count, 0)
                                 : owner->recv((SOCKET)loan->descriptor, buffer, (int)count, 0);
        result->error = result->length == SOCKET_ERROR ? owner->socket_error() : 0;
        result->windows_error = GetLastError();
        result->console_abort = 0;
    } else {
        result->length = writing ? owner->write((int)loan->descriptor, buffer, count)
                                 : owner->read((int)loan->descriptor, buffer, count);
        int error = *owner->error();
        result->windows_error = GetLastError();
        result->error = result->length == -1 ? error : 0;
        if (writing && result->error == EINVAL && result->windows_error == ERROR_NO_DATA)
            result->error = EPIPE;
        result->console_abort = !writing && result->length == 0 && count != 0 && loan->standard_input &&
                                result->windows_error == ERROR_OPERATION_ABORTED;
    }
}

__declspec(dllexport) void thc_windows_io_release(struct io_owner *owner, const struct io_loan *loan) {
    int error = *owner->error();
    DWORD windows_error = GetLastError();
    if (loan->socket) owner->socket_close((SOCKET)loan->descriptor);
    else owner->close((int)loan->descriptor);
    *owner->error() = error;
    SetLastError(windows_error);
}
