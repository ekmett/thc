// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <errno.h>
#include <io.h>
#include <limits.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>

_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8 && sizeof(size_t) == 8 &&
               sizeof(int) == 4 && sizeof(long) == 4 && sizeof(bool) == 1,
               "Windows descriptor constants require the x86_64 LLP64 ABI");
_Static_assert(_Generic(&_read, int (*)(int, void *, unsigned int): 1, default: 0),
               "Windows CRT _read is not the POSIX ssize_t/size_t declaration");
_Static_assert(_Generic(&_write, int (*)(int, const void *, unsigned int): 1, default: 0),
               "Windows CRT _write is not the POSIX ssize_t/size_t declaration");

int main(void) {
    printf("{\"widths\":{\"charBits\":%d,\"pointer\":%zu,\"int\":%zu,\"long\":%zu,"
           "\"bool\":%zu,\"size\":%zu,\"crtReadResult\":%zu,\"crtReadCount\":%zu},"
           "\"errno\":{\"ENOENT\":%d,\"EACCES\":%d,\"EEXIST\":%d,\"EBADF\":%d,"
           "\"EINVAL\":%d,\"EIO\":%d,\"ENOTSUP\":%d,\"EBUSY\":%d,\"EISDIR\":%d,"
           "\"ENOTTY\":%d,\"ESPIPE\":%d,\"EMFILE\":%d},"
           "\"seek\":{\"SEEK_SET\":%d,\"SEEK_CUR\":%d,\"SEEK_END\":%d}}\n",
           CHAR_BIT, sizeof(void *), sizeof(int), sizeof(long), sizeof(bool), sizeof(size_t),
           sizeof(int), sizeof(unsigned int), ENOENT, EACCES, EEXIST, EBADF, EINVAL,
           EIO, ENOTSUP, EBUSY, EISDIR, ENOTTY, ESPIPE, EMFILE, SEEK_SET, SEEK_CUR, SEEK_END);
    return 0;
}
