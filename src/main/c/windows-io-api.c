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
// CRT calls bind to the selected package DLL's actual errno provider. Never use
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
    HANDLE standard[3];
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
struct io_loan { uintptr_t descriptor; int socket, standard_endpoints; };
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

// A package need not import every operation this boundary can perform.
// Resolve within its actual CRT, rejecting a conflicting resolved import if
// present. Missing operations are checked by their owning operation below.
static void *crt_operation(HMODULE package, HMODULE crt, const char *name) {
    void *operation = (void *)(uintptr_t)GetProcAddress(crt, name);
    void *selected = imported(package, name);
    return selected && selected != operation ? NULL : operation;
}

__declspec(dllexport) void *thc_windows_io_bind(const wchar_t *loaded_package, DWORD *error) {
    HMODULE package = NULL, crt = NULL;
    *error = 0;
    if (!GetModuleHandleExW(0, loaded_package, &package)) { *error = GetLastError(); return NULL; }
    struct io_owner *owner = calloc(1, sizeof(*owner));
    if (!owner) { FreeLibrary(package); *error = ERROR_NOT_ENOUGH_MEMORY; return NULL; }
    owner->package = package;
    owner->error = (errno_fn)imported(package, "_errno");
    owner->recv = (recv_fn)imported(package, "recv");
    owner->send = (send_fn)imported(package, "send");
    owner->socket_error = (socket_error_fn)imported(package, "WSAGetLastError");
    owner->socket_close = (socket_close_fn)imported(package, "closesocket");
    // GHC's bundled MinGW has the Win10 declaration but no import-library
    // entry. Resolve the OS operation from its existing fixed system module.
    owner->compare = (compare_fn)GetProcAddress(GetModuleHandleW(L"kernelbase.dll"), "CompareObjectHandles");
    if (!owner->error ||
        !GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                           (LPCWSTR)(uintptr_t)owner->error, &crt)) goto mismatch;
    // The package's CRT owner supplies acquisition and retirement too.
    owner->read = (read_fn)crt_operation(package, crt, "_read");
    owner->write = (write_fn)crt_operation(package, crt, "_write");
    owner->duplicate = (dup_fn)crt_operation(package, crt, "_dup");
    owner->close = (close_fn)crt_operation(package, crt, "_close");
    owner->handle = (handle_fn)crt_operation(package, crt, "_get_osfhandle");
    owner->local_validation = (local_validation_fn)crt_operation(package, crt, "_set_thread_local_invalid_parameter_handler");
    // Retain the endpoint objects as well as the package. Re-reading a retired
    // GetStdHandle value could mistake a reused kernel handle for stdin.
    const DWORD endpoints[] = {STD_INPUT_HANDLE, STD_OUTPUT_HANDLE, STD_ERROR_HANDLE};
    for (unsigned index = 0; index < 3; ++index) {
        HANDLE endpoint = GetStdHandle(endpoints[index]);
        if (endpoint && endpoint != INVALID_HANDLE_VALUE &&
            !DuplicateHandle(GetCurrentProcess(), endpoint, GetCurrentProcess(), &owner->standard[index],
                             0, FALSE, DUPLICATE_SAME_ACCESS) && GetLastError() != ERROR_INVALID_HANDLE) {
            *error = GetLastError();
            goto failed;
        }
    }
    return owner;
mismatch:
    *error = ERROR_INVALID_DATA;
failed:
    for (unsigned index = 0; index < 3; ++index) if (owner->standard[index]) CloseHandle(owner->standard[index]);
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
    for (unsigned index = 0; index < 3; ++index) if (owner->standard[index]) CloseHandle(owner->standard[index]);
    FreeLibrary(owner->package);
    free(owner);
}

// Ordinary selected-package calls retain their own implementation. These TLS
// operations run on that call's native origin, before any Java reconciliation.
__declspec(dllexport) void thc_windows_io_seed_errno(struct io_owner *owner, int error) {
    *owner->error() = error;
}
__declspec(dllexport) void thc_windows_io_capture_error(struct io_owner *owner, DWORD *result) {
    DWORD windows_error = GetLastError();
    result[0] = (DWORD)*owner->error();
    result[1] = windows_error;
}

__declspec(dllexport) int thc_windows_io_acquire(struct io_owner *owner, int fd, int socket,
                                              struct io_loan *loan) {
    loan->socket = socket != 0;
    loan->standard_endpoints = 0;
    if (loan->socket) {
        if (!owner->socket_close || !owner->socket_error) return WSAEOPNOTSUPP;
        WSAPROTOCOL_INFOW protocol;
        SOCKET original = (SOCKET)(uintptr_t)(uint32_t)fd;
        if (WSADuplicateSocketW(original, GetCurrentProcessId(), &protocol)) return WSAGetLastError();
        SOCKET copy = WSASocketW(FROM_PROTOCOL_INFO, FROM_PROTOCOL_INFO, FROM_PROTOCOL_INFO,
                                &protocol, 0, WSA_FLAG_OVERLAPPED);
        if (copy == INVALID_SOCKET) return WSAGetLastError();
        loan->descriptor = (uintptr_t)copy;
    } else {
        if (fd < 0) return EBADF;
        if (!owner->duplicate || !owner->close || !owner->handle || !owner->local_validation || !owner->compare)
            return ENOSYS;
        int error;
        int copy = duplicate(owner, fd, &error);
        if (copy < 0) return error;
        // Do not query a possibly closed original after duplication. The CRT
        // owns the copy, and CompareObjectHandles tests the actual kernel object.
        intptr_t handle = owner->handle(copy);
        for (unsigned index = 0; index < 3; ++index) {
            HANDLE endpoint = owner->standard[index];
            if (handle != -1 && endpoint && owner->compare((HANDLE)handle, endpoint))
                loan->standard_endpoints |= 1 << index;
        }
        loan->descriptor = (uintptr_t)copy;
    }
    return 0;
}

__declspec(dllexport) void thc_windows_io_transfer(struct io_owner *owner, const struct io_loan *loan,
                                                 int writing, unsigned count, void *buffer,
                                                 struct io_result *result) {
    // Successful CRT EOF must not inherit an earlier console-abort code.
    SetLastError(ERROR_SUCCESS);
    if (loan->socket ? !(writing ? owner->send != NULL : owner->recv != NULL) || !owner->socket_error
                     : !(writing ? owner->write != NULL : owner->read != NULL)) {
        result->length = -1;
        result->error = loan->socket ? WSAEOPNOTSUPP : ENOSYS;
        result->windows_error = ERROR_PROC_NOT_FOUND;
        result->console_abort = 0;
        return;
    }
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
        result->console_abort = !writing && result->length == 0 && count != 0 && (loan->standard_endpoints & 1) &&
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

// OS callbacks never enter Truffle or dereference a GHC StablePtr. Windows
// supplies only the event code, not a registration generation. Ownership is
// captured when the process-lifetime dispatcher enters the locked registry.
struct console_event { uint64_t sequence, generation; DWORD event, reserved; };
struct console_pending { struct console_event event; struct console_pending *next; };
struct console_owner {
    SRWLOCK lock;
    HANDLE ready;
    int action, retired;
    DWORD failure;
    uint64_t generation, sequence;
    struct console_pending *head, *tail;
};
_Static_assert(sizeof(struct console_event) == 24, "Console event transport requires Win64 layouts");
static SRWLOCK console_registry = SRWLOCK_INIT;
static int console_registered;
static struct console_owner *console_active;

static BOOL WINAPI console_dispatch(DWORD event) {
    BOOL handled = FALSE;
    AcquireSRWLockExclusive(&console_registry);
    struct console_owner *owner = console_active;
    if (!owner) { ReleaseSRWLockExclusive(&console_registry); return FALSE; }
    AcquireSRWLockExclusive(&owner->lock);
    // Match GHC's deliberate refusal of CLOSE. DFL continues the existing JVM
    // handler chain; IGN uses this owner's action, not NULL's inheritable
    // process-wide ignore bit. The registry remains locked until the snapshot
    // is queued, so stop cannot free or replace an owner under this callback.
    if (!owner->retired && event != CTRL_CLOSE_EVENT && owner->action != -1) {
        struct console_pending *pending = calloc(1, sizeof(*pending));
        if (!pending) owner->failure = ERROR_NOT_ENOUGH_MEMORY;
        else {
            pending->event.sequence = ++owner->sequence;
            pending->event.generation = owner->action == -4 ? owner->generation : 0;
            pending->event.event = event;
            if (owner->tail) owner->tail->next = pending;
            else owner->head = pending;
            owner->tail = pending;
            handled = TRUE;
        }
        SetEvent(owner->ready);
    }
    ReleaseSRWLockExclusive(&owner->lock);
    ReleaseSRWLockExclusive(&console_registry);
    return handled;
}

__declspec(dllexport) void *thc_windows_io_console_open(DWORD *error) {
    struct console_owner *owner = NULL;
    *error = 0;
    AcquireSRWLockExclusive(&console_registry);
    if (console_active) *error = ERROR_BUSY;
    else {
        owner = calloc(1, sizeof(*owner));
        if (!owner) *error = ERROR_NOT_ENOUGH_MEMORY;
        else {
            InitializeSRWLock(&owner->lock);
            owner->action = -1;
            owner->ready = CreateEventW(NULL, TRUE, FALSE, NULL);
            if (!owner->ready) *error = GetLastError();
            else if (!console_registered) {
                HMODULE image = NULL;
                // Windows does not document an unregister-joins guarantee.
                // Keep the sole dispatcher and its code valid until process
                // exit; detached owners themselves are reclaimable.
                if (!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_PIN,
                                       (LPCWSTR)(uintptr_t)console_dispatch, &image) ||
                    !SetConsoleCtrlHandler(console_dispatch, TRUE)) *error = GetLastError();
                else console_registered = 1;
            }
            if (*error) {
                if (owner->ready) CloseHandle(owner->ready);
                free(owner); owner = NULL;
            } else console_active = owner;
        }
    }
    ReleaseSRWLockExclusive(&console_registry);
    return owner;
}

__declspec(dllexport) DWORD thc_windows_io_console_install(struct console_owner *owner, int action, uint64_t generation) {
    DWORD error = 0;
    AcquireSRWLockExclusive(&owner->lock);
    if (owner->retired) error = ERROR_INVALID_HANDLE;
    else if ((action != -1 && action != -2 && action != -4) || (action == -4 && !generation)) error = ERROR_INVALID_PARAMETER;
    else { owner->action = action; owner->generation = generation; }
    ReleaseSRWLockExclusive(&owner->lock);
    return error;
}

__declspec(dllexport) uint64_t thc_windows_io_console_sequence(struct console_owner *owner) {
    AcquireSRWLockShared(&owner->lock);
    uint64_t sequence = owner->sequence;
    ReleaseSRWLockShared(&owner->lock);
    return sequence;
}

__declspec(dllexport) uint64_t thc_windows_io_console_pending(struct console_owner *owner, uint64_t generation) {
    uint64_t count = 0;
    AcquireSRWLockShared(&owner->lock);
    for (struct console_pending *entry = owner->head; entry; entry = entry->next)
        if (entry->event.generation == generation) ++count;
    ReleaseSRWLockShared(&owner->lock);
    return count;
}

// Poll/drain on a physical reader; only this reader waits on the OS event.
// Return 1 event, 0 explicit wake/retirement, negative native failure.
__declspec(dllexport) int thc_windows_io_console_take(struct console_owner *owner, struct console_event *event) {
    int result = 0;
    AcquireSRWLockExclusive(&owner->lock);
    if (owner->failure) result = -(int)owner->failure;
    else if (!owner->retired && owner->head) {
        struct console_pending *entry = owner->head;
        *event = entry->event;
        owner->head = entry->next;
        if (!owner->head) owner->tail = NULL;
        free(entry); result = 1;
    }
    if (!owner->head && !owner->retired) ResetEvent(owner->ready);
    ReleaseSRWLockExclusive(&owner->lock);
    return result;
}
__declspec(dllexport) DWORD thc_windows_io_console_wait(struct console_owner *owner) {
    DWORD result = WaitForSingleObject(owner->ready, INFINITE);
    return result == WAIT_OBJECT_0 ? 0 : GetLastError();
}
__declspec(dllexport) void thc_windows_io_console_wake(struct console_owner *owner) { SetEvent(owner->ready); }

// Detach under the same lock used by dispatcher entry. A callback already
// observed is finished with its owner before stop returns; an unobserved OS
// event has no earlier owner identity. The reader's HANDLE remains valid until
// close after its native wait has ended. External console reassociation APIs,
// which reset the handler table, are not part of this helper's owned API.
__declspec(dllexport) DWORD thc_windows_io_console_stop(struct console_owner *owner) {
    AcquireSRWLockExclusive(&console_registry);
    AcquireSRWLockExclusive(&owner->lock);
    if (!owner->retired) {
        owner->retired = 1;
        if (console_active == owner) console_active = NULL;
        SetEvent(owner->ready);
    }
    ReleaseSRWLockExclusive(&owner->lock);
    ReleaseSRWLockExclusive(&console_registry);
    return 0;
}
__declspec(dllexport) void thc_windows_io_console_close(struct console_owner *owner) {
    // Stop must precede close. No callbacks use the HANDLE after retirement.
    AcquireSRWLockExclusive(&owner->lock);
    while (owner->head) {
        struct console_pending *entry = owner->head;
        owner->head = entry->next; free(entry);
    }
    owner->tail = NULL;
    if (owner->ready) { CloseHandle(owner->ready); owner->ready = NULL; }
    ReleaseSRWLockExclusive(&owner->lock);
    free(owner);
}
