// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#define _DEFAULT_SOURCE 1
#define _DARWIN_C_SOURCE 1
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <termios.h>

_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8 && sizeof(long) == 8 && sizeof(int) == 4,
               "termios requires native LP64");
#if defined(__linux__)
_Static_assert(sizeof(tcflag_t) == 4 && (tcflag_t)-1 > 0 && sizeof(cc_t) == 1,
               "termios requires the original Linux Word32/Word8 ABI");
#endif
int main(void) {
  printf("{\"size\":%zu,\"alignment\":%zu,\"lflagOffset\":%zu,\"lflagBytes\":%zu,"
         "\"ccOffset\":%zu,\"ccBytes\":%zu,\"ccCount\":%zu,"
         "\"echo\":%d,\"icanon\":%d,\"vmin\":%d,\"vtime\":%d,\"tcsanow\":%d}\n",
         sizeof(struct termios), _Alignof(struct termios), offsetof(struct termios, c_lflag), sizeof(tcflag_t),
         offsetof(struct termios, c_cc), sizeof(cc_t), sizeof(((struct termios *)0)->c_cc),
         (int)ECHO, (int)ICANON, (int)VMIN, (int)VTIME, (int)TCSANOW);
}
