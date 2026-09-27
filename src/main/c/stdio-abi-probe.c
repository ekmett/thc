// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#define _POSIX_C_SOURCE 200809L
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdbool.h>
#include <signal.h>
#include <stdio.h>
#include <sys/types.h>

// HsBase uses CONST_O_BINARY, configured to zero when the host has no O_BINARY.
#ifndef O_BINARY
#define O_BINARY 0
#endif

_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8 && sizeof(int) == 4 && sizeof(bool) == 1 &&
               sizeof(size_t) == 8 && sizeof(ssize_t) == 8 && (ssize_t)-1 < 0,
               "Original stdio calls require the supported LP64 host ABI");

int main(void) {
    printf("{\"widths\":{\"charBits\":%d,\"pointer\":%zu,\"int\":%zu,"
           "\"bool\":%zu,\"size\":%zu,\"ssize\":%zu},\"errno\":{\"ENOENT\":%d,"
           "\"EACCES\":%d,\"EEXIST\":%d,\"EBADF\":%d,\"EINVAL\":%d,"
           "\"EIO\":%d,\"ENOTSUP\":%d,\"EBUSY\":%d,\"EISDIR\":%d,\"ENOTTY\":%d,\"ESPIPE\":%d,\"EMFILE\":%d},"
           "\"seek\":{\"SEEK_SET\":%d,\"SEEK_CUR\":%d,\"SEEK_END\":%d},"
           "\"open\":{\"modeBytes\":%zu,\"O_ACCMODE\":%d,\"O_RDONLY\":%d,\"O_WRONLY\":%d,\"O_RDWR\":%d,\"O_APPEND\":%d,"
           "\"O_CREAT\":%d,\"O_EXCL\":%d,\"O_BINARY\":%d,\"O_TRUNC\":%d,\"O_NOCTTY\":%d,\"O_NONBLOCK\":%d,\"F_GETFL\":%d,\"F_SETFL\":%d,"
           "\"F_SETFD\":%d,\"FD_CLOEXEC\":%d},\"siginfoBytes\":%zu}\n",
           CHAR_BIT, sizeof(void *), sizeof(int), sizeof(bool), sizeof(size_t), sizeof(ssize_t),
           ENOENT, EACCES, EEXIST, EBADF, EINVAL, EIO, ENOTSUP, EBUSY, EISDIR, ENOTTY, ESPIPE, EMFILE,
           SEEK_SET, SEEK_CUR, SEEK_END, sizeof(mode_t), O_ACCMODE, O_RDONLY, O_WRONLY, O_RDWR, O_APPEND,
           O_CREAT, O_EXCL, O_BINARY, O_TRUNC, O_NOCTTY, O_NONBLOCK, F_GETFL, F_SETFL, F_SETFD, FD_CLOEXEC, sizeof(siginfo_t));
    return 0;
}
