# -*- Autoconf -*-
# Process this file with autoconf to produce a configure script.

AC_PREREQ(2.64)
AC_INIT([thc],[0.1],[ekmett@gmail.com])
AC_SUBST(THC_COPYRIGHT,["Copyright (c) 2015 Edward Kmett. All Rights Reserved."])
AC_COPYRIGHT([Copyright (C) 2015 Edward Kmett. All Rights Reserved."])
AC_CONFIG_AUX_DIR([bin])
AC_CONFIG_HEADER([rts/config.h])
AC_CANONICAL_SYSTEM
AC_CONFIG_SRCDIR([rts/gc.cpp])

AC_LANG([C++])
AC_PROG_CXX
AM_PROG_CC_C_O

LT_INIT
AC_PROG_LIBTOOL

AX_CXX_COMPILE_STDCXX_11

AX_C_HAVE_ATTRIBUTE
AX_C_HAVE_ATTRIBUTE_COLD

AM_INIT_AUTOMAKE([foreign subdir-objects])

AC_CHECK_HEADERS_ONCE(pthread.h)
AX_CXX_PTHREAD

AC_FUNC_MMAP
AC_FUNC

#######################################################################################
# manual config header entries
#######################################################################################

AH_TOP([#ifndef INCLUDED_RTS_CONFIG_H
#define INCLUDED_RTS_CONFIG_H
])

AH_VERBATIM([GCC_VERSION],
[/* Simplified version checking for GCC */
#ifdef __GNUC__
#define GCC_VERSION (__GNUC__ * 10000 + __GNUC_MINOR__ * 100 + __GNUC_PATCHLEVEL__)
#endif
])

# abuse GNU attributes. NB. the __attribute__ macro below could encompass all of these uses.
AH_VERBATIM([HAVE_ATTRIBUTE],
[/* Encapsulate a number of optional GNU attributes */
#ifndef HAVE_ATTRIBUTE
# define __attribute__(x)
#endif

# define THC_NORETURN __attribute__ ((noreturn))
# define THC_NOTHROW __attribute__ ((nothrow))
# define THC_ALWAYS_INLINE __attribute__ ((always_inline)) inline
# define THC_UNUSED __attribute__ ((unused))
# define THC_PURE __attribute__ ((pure))
# define THC_ALIGNED(x) __attribute__ ((aligned(x)))

# ifdef HAVE_ATTRIBUTE_COLD
#  define THC_NORETURN_COLD __attribute__ ((noreturn,cold))
#  define THC_COLD __attribute__ ((cold))
# else 
#  define THC_NORETURN_COLD NORETURN
#  define THC_COLD
# endif
])

AH_BOTTOM([#endif // INCLUDED_RTS_CONFIG_H])

AC_CHECK_FUNCS_ONCE(remap_file_pages munmap mremap)

AC_OUTPUT([Makefile]);
