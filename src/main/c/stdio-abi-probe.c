// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <sys/types.h>

_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8 && sizeof(int) == 4 &&
               sizeof(size_t) == 8 && sizeof(ssize_t) == 8 && (ssize_t)-1 < 0,
               "Original stdio calls require the supported LP64 host ABI");

int main(void) {
    printf("{\"widths\":{\"charBits\":%d,\"pointer\":%zu,\"int\":%zu,"
           "\"size\":%zu,\"ssize\":%zu},\"errno\":{\"ENOENT\":%d,"
           "\"EACCES\":%d,\"EEXIST\":%d,\"EBADF\":%d,\"EINVAL\":%d,"
           "\"EIO\":%d,\"ENOTSUP\":%d,\"EBUSY\":%d,\"EISDIR\":%d,\"ENOTTY\":%d}}\n",
           CHAR_BIT, sizeof(void *), sizeof(int), sizeof(size_t), sizeof(ssize_t),
           ENOENT, EACCES, EEXIST, EBADF, EINVAL, EIO, ENOTSUP, EBUSY, EISDIR, ENOTTY);
    return 0;
}
