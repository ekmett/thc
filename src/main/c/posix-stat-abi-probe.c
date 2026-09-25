// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#define _DEFAULT_SOURCE 1
#define _DARWIN_C_SOURCE 1
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>

// Probe the actual native structure; no copied Linux/macOS layout constants.
_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8 && sizeof(int) == 4,
               "stat requires a native LP64 target");
#if defined(__linux__)
_Static_assert(sizeof(dev_t) == 8 && (dev_t)-1 > 0 && sizeof(ino_t) == 8 && (ino_t)-1 > 0 &&
               sizeof(mode_t) == 4 && (mode_t)-1 > 0 && sizeof(off_t) == 8 && (off_t)-1 < 0,
               "stat requires the exact original Linux scalar ABI");
#endif

#define FIELD(name) printf("\"" #name "\":{\"offset\":%zu,\"width\":%zu}", \
                          offsetof(struct stat, name), sizeof(((struct stat *)0)->name))
int main(void) {
  printf("{\"size\":%zu,\"alignment\":%zu,\"fields\":{", sizeof(struct stat), _Alignof(struct stat));
  FIELD(st_dev); printf(","); FIELD(st_ino); printf(","); FIELD(st_mode); printf(","); FIELD(st_size);
  printf("},\"types\":{\"mask\":%u,\"regular\":%u,\"character\":%u,\"block\":%u,"
         "\"directory\":%u,\"fifo\":%u,\"socket\":%u}}\n",
         (unsigned)S_IFMT, (unsigned)S_IFREG, (unsigned)S_IFCHR, (unsigned)S_IFBLK,
         (unsigned)S_IFDIR, (unsigned)S_IFIFO, (unsigned)S_IFSOCK);
}
