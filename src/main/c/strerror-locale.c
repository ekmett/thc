// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

/* GHC startup leaves LC_MESSAGES at C. Scope that category to the calling
 * native thread without changing the embedding process's global locale. */
#define _POSIX_C_SOURCE 200809L
#include <locale.h>
#if defined(__APPLE__)
#include <xlocale.h>
#endif
#include <stdlib.h>

struct thc_strerror_locale {
  locale_t previous;
  locale_t current;
};

void *thc_strerror_locale_enter(void) {
  locale_t previous = uselocale((locale_t)0);
  if (!previous) return NULL;
  /* newlocale with a null base resets every unspecified category to C.
   * Copy the current thread locale so only LC_MESSAGES changes. */
  locale_t base = duplocale(previous);
  if (!base) return NULL;
  locale_t current = newlocale(LC_MESSAGES_MASK, "C", base);
  if (!current) { freelocale(base); return NULL; }
  struct thc_strerror_locale *scope = malloc(sizeof(*scope));
  if (!scope) { freelocale(current); return NULL; }
  if (!uselocale(current)) { free(scope); freelocale(current); return NULL; }
  scope->previous = previous;
  scope->current = current;
  return scope;
}

int thc_strerror_locale_leave(void *opaque) {
  struct thc_strerror_locale *scope = opaque;
  if (!scope || !uselocale(scope->previous)) return 0;
  freelocale(scope->current);
  free(scope);
  return 1;
}
