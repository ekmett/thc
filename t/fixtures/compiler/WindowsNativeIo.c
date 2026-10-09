// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <winsock2.h>
#include <windows.h>
#include <errno.h>
#include <fcntl.h>
#include <io.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/stat.h>
#include <stdlib.h>

// Real SDK operations make the provider's CRT and WinSock imports observable.
// This native ABI fixture has no Core model or replacement library functions.
_Static_assert(sizeof(int) == 4 && sizeof(void *) == 8 && sizeof(SOCKET) == 8, "Win64 fixture ABI");
__declspec(dllexport) int fixture_open(const wchar_t *path) { return _wopen(path, _O_CREAT | _O_TRUNC | _O_RDWR | _O_BINARY, _S_IREAD | _S_IWRITE); }
__declspec(dllexport) int fixture_close(int fd) { return _close(fd); }
__declspec(dllexport) int fixture_seek(int fd) { return _lseek(fd, 0, SEEK_SET); }
__declspec(dllexport) int fixture_read(int fd, void *bytes, unsigned count) { return _read(fd, bytes, count); }
__declspec(dllexport) int fixture_write(int fd, const void *bytes, unsigned count) { return _write(fd, bytes, count); }
__declspec(dllexport) int fixture_errno(void) { return *_errno(); }
__declspec(dllexport) int fixture_pipe(int *fds) { return _pipe(fds, 4096, _O_BINARY); }
__declspec(dllexport) int fixture_recv(int fd, void *bytes, int count) { return recv((SOCKET)(uintptr_t)(uint32_t)fd, bytes, count, 0); }
__declspec(dllexport) int fixture_send(int fd, const void *bytes, int count) { return send((SOCKET)(uintptr_t)(uint32_t)fd, bytes, count, 0); }
__declspec(dllexport) int fixture_socket_close(int fd) { return closesocket((SOCKET)(uintptr_t)(uint32_t)fd); }
__declspec(dllexport) int fixture_socket_error(void) { return WSAGetLastError(); }

extern int *(__cdecl *__imp__errno)(void);
typedef _invalid_parameter_handler (__cdecl *get_validation_fn)(void);
typedef _invalid_parameter_handler (__cdecl *set_validation_fn)(_invalid_parameter_handler);
static _Thread_local _invalid_parameter_handler saved_validation, saved_global;
static void __cdecl sentinel(const wchar_t *expression, const wchar_t *function,
                             const wchar_t *file, unsigned line, uintptr_t reserved) {
    (void)expression; (void)function; (void)file; (void)line; (void)reserved;
    // No fixture SDK call should use this callback. The IO boundary's scoped
    // closed-descriptor recovery must restore this exact prior identity.
    RaiseFailFastException(NULL, NULL, 0);
}
static HMODULE crt_module(void) {
    HMODULE module = NULL;
    GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS | GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                       (LPCWSTR)(uintptr_t)__imp__errno, &module);
    return module;
}
__declspec(dllexport) int fixture_validation_begin(void) {
    HMODULE crt = crt_module();
    set_validation_fn set = (set_validation_fn)GetProcAddress(crt, "_set_thread_local_invalid_parameter_handler");
    if (!set) return -1;
    saved_global = _get_invalid_parameter_handler();
    saved_validation = set(sentinel);
    return 0;
}
__declspec(dllexport) int fixture_validation_end(void) {
    HMODULE crt = crt_module();
    get_validation_fn get = (get_validation_fn)GetProcAddress(crt, "_get_thread_local_invalid_parameter_handler");
    set_validation_fn set = (set_validation_fn)GetProcAddress(crt, "_set_thread_local_invalid_parameter_handler");
    int preserved = get() == sentinel && _get_invalid_parameter_handler() == saved_global;
    set(saved_validation);
    return preserved;
}

__declspec(dllexport) int fixture_socket_pair(int *fds) {
    WSADATA data;
    int error = WSAStartup(MAKEWORD(2, 2), &data);
    if (error) return error;
    SOCKET listener = INVALID_SOCKET, client = INVALID_SOCKET, server = INVALID_SOCKET;
    struct sockaddr_in address = {0};
    address.sin_family = AF_INET;
    address.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    int size = sizeof(address);
    listener = socket(AF_INET, SOCK_STREAM, 0);
    if (listener == INVALID_SOCKET || bind(listener, (struct sockaddr *)&address, size) ||
        listen(listener, 1) || getsockname(listener, (struct sockaddr *)&address, &size)) goto failed;
    client = socket(AF_INET, SOCK_STREAM, 0);
    if (client == INVALID_SOCKET || connect(client, (struct sockaddr *)&address, size)) goto failed;
    server = accept(listener, NULL, NULL);
    if (server == INVALID_SOCKET) goto failed;
    if ((SOCKET)(uint32_t)client != client || (SOCKET)(uint32_t)server != server) { error = WSAEINVAL; goto cleanup; }
    fds[0] = (int)(uint32_t)client;
    fds[1] = (int)(uint32_t)server;
    closesocket(listener);
    return 0;
failed:
    error = WSAGetLastError();
cleanup:
    if (listener != INVALID_SOCKET) closesocket(listener);
    if (client != INVALID_SOCKET) closesocket(client);
    if (server != INVALID_SOCKET) closesocket(server);
    WSACleanup();
    return error;
}
__declspec(dllexport) int fixture_socket_cleanup(void) { return WSACleanup(); }
