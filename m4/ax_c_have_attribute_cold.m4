AC_DEFUN([AX_C_HAVE_ATTRIBUTE_COLD], [
  AC_MSG_CHECKING(for __attribute__((cold)))
  AC_CACHE_VAL(ac_cv___attribute__cold, [
    # AC_LANG_PUSH([C])
    save_CFLAGS="$CFLAGS"
    save_CPPFLAGS="$CPPFLAGS"
    CFLAGS="$CFLAGS -Werror"
    CPPFLAGS="$CFLAGS -Werror"
    AC_TRY_COMPILE(
      [#include <stdlib.h>
       static void foo(void) __attribute__ ((cold));
       void foo(void) { exit(1); }],
      [],
      ac_cv___attribute__cold=yes,
      ac_cv___attribute__cold=no
    )])
    CPPFLAGS="$save_CPPFLAGS"
    CFLAGS="$save_CFLAGS"
    # AC_LANG_POP([C])
  if test "$ac_cv___attribute__cold" = "yes"; then
    AC_DEFINE(HAVE_ATTRIBUTE_COLD, 1, [define if your compiler has __attribute__((cold))])
  fi
  AC_MSG_RESULT($ac_cv___attribute__cold)
])
