AC_DEFUN([AX_C_HAVE_ATTRIBUTE], [
  AC_MSG_CHECKING(for __attribute__)
  AC_CACHE_VAL(ac_cv___attribute__, [
    AC_TRY_COMPILE(
      [#include <stdlib.h>
       static void foo(void) __attribute__ ((unused));
       void foo(void) { exit(1); }],
      [],
      ac_cv___attribute__=yes,
      ac_cv___attribute__=no
    )])
  if test "$ac_cv___attribute__" = "yes"; then
    AC_DEFINE(HAVE_ATTRIBUTE, 1, [define if your compiler has __attribute__])
  fi
  AC_MSG_RESULT($ac_cv___attribute__)
])
