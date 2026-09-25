// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

/* Transport for the four original GHC.Internal.IO.Encoding.Iconv imports.
 * GHC's hs_iconv_* wrappers delegate directly to libc iconv. This adapter does
 * the same conversion; it only moves managed bytes across the native boundary.
 * No managed pointer is passed to iconv or retained by a native descriptor. */
#define _GNU_SOURCE
#include <errno.h>
#include <iconv.h>
#include <langinfo.h>
#include <limits.h>
#include <locale.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#if !defined(__linux__) || !defined(__GLIBC__)
#error Original native iconv currently requires Linux glibc
#endif

_Static_assert(CHAR_BIT == 8 && sizeof(void *) == 8 && sizeof(long) == 8 &&
               sizeof(size_t) == 8 && sizeof(int) == 4 && sizeof(iconv_t) == 8,
               "Original iconv requires the native LP64 ABI");

/* locale_t and iconv_t stay native. Only Kotlin's context registry exposes a
 * guest CLong token. Initialization uses the environment's LC_CTYPE, as GHC
 * startup does, without mutating the embedding process's global locale. */
void *thc_iconv_locale_new(void) {
  return newlocale(LC_CTYPE_MASK, "", (locale_t)0);
}
void thc_iconv_locale_free(void *locale) { freelocale((locale_t)locale); }
long thc_iconv_locale_size(void *locale) {
  return (long)strlen(nl_langinfo_l(CODESET, (locale_t)locale)) + 1;
}
void thc_iconv_locale_copy(void *locale, unsigned char *managed) {
  const char *name = nl_langinfo_l(CODESET, (locale_t)locale);
  /* Volatile accesses prevent clang replacing managed/native copies with a
   * host memcpy call. Sulong executes these accesses over buffer interop. */
  volatile unsigned char *out = managed;
  do { *out++ = (unsigned char)*name; } while (*name++);
}

static char *native_copy(const unsigned char *managed, size_t count) {
  char *native = malloc(count ? count : 1);
  if (native) {
    const volatile unsigned char *in = managed;
    for (size_t i = 0; i < count; ++i) native[i] = (char)in[i];
  }
  return native;
}

struct thc_iconv_handle { iconv_t descriptor; locale_t locale; };

void *thc_iconv_open(void *locale, const unsigned char *to, long to_size,
                    const unsigned char *from, long from_size, long *error) {
  char *native_to = native_copy(to, (size_t)to_size);
  char *native_from = native_copy(from, (size_t)from_size);
  struct thc_iconv_handle *handle = malloc(sizeof(*handle));
  if (!native_to || !native_from || !handle) {
    free(native_to); free(native_from); free(handle);
    error[0] = ENOMEM;
    return NULL;
  }
  locale_t previous = uselocale((locale_t)locale);
  handle->locale = (locale_t)locale;
  handle->descriptor = iconv_open(native_to, native_from);
  int saved_errno = errno;
  uselocale(previous);
  free(native_to); free(native_from);
  if (handle->descriptor == (iconv_t)-1) {
    free(handle);
    error[0] = saved_errno;
    return NULL;
  }
  error[0] = 0;
  return handle;
}

long thc_iconv_close(void *opaque, long *error) {
  struct thc_iconv_handle *handle = opaque;
  int result = iconv_close(handle->descriptor);
  int saved_errno = errno;
  free(handle);
  error[0] = result == -1 ? saved_errno : 0;
  return result;
}

/* result = { size_t result, errno on failure, input left, output left }.
 * The caller checks and allocates every managed region BEFORE entering C.
 * reset=1 supplies NULL input; discard=1 also supplies NULL output, the two
 * standard reset forms. Partial conversion is copied back even on failure. */
void thc_iconv_convert(void *opaque, const unsigned char *managed_in, long in_size,
                       unsigned char *managed_out, long out_size,
                       int reset, int discard, long *result) {
  char *input = native_copy(managed_in, (size_t)in_size);
  char *output = malloc(out_size ? (size_t)out_size : 1);
  size_t in_left = (size_t)in_size, out_left = (size_t)out_size;
  if (!input || !output) {
    free(input); free(output);
    result[0] = -1; result[1] = ENOMEM;
    result[2] = (long)in_left; result[3] = (long)out_left;
    return;
  }
  char *in_cursor = input, *out_cursor = output;
  struct thc_iconv_handle *handle = opaque;
  /* Transliteration can consult LC_CTYPE during conversion, not only open. */
  locale_t previous = uselocale(handle->locale);
  size_t converted = iconv(handle->descriptor, reset ? NULL : &in_cursor,
                           reset ? NULL : &in_left, discard ? NULL : &out_cursor,
                           discard ? NULL : &out_left);
  int saved_errno = errno;
  uselocale(previous);
  volatile unsigned char *out = managed_out;
  for (size_t i = 0; i < (size_t)out_size - out_left; ++i) out[i] = (unsigned char)output[i];
  free(input); free(output);
  result[0] = (long)converted;
  result[1] = converted == (size_t)-1 ? saved_errno : 0;
  result[2] = (long)in_left; result[3] = (long)out_left;
}
