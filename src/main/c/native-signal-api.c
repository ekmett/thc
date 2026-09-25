// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#define _GNU_SOURCE 1
#include <errno.h>
#include <signal.h>
#include <stddef.h>
#include <stdint.h>

#if !defined(__linux__) || !defined(__x86_64__) || !defined(__GLIBC__)
#error "The bounded signal-mask bridge requires Linux x86_64 glibc"
#endif

_Static_assert(NSIG == 65 && __SIGRTMIN == 32, "Unsupported kernel signal range");
_Static_assert(sizeof(sigset_t) % 8 == 0, "Unsupported sigset_t size");

int64_t thc_signal_size(void) { return sizeof(sigset_t); }

/* Query, validation and update are one synchronous native extent on the
 * calling platform thread. No saved-mask token authorizes a later update.
 * -2 is an explicit domain rejection, not a fabricated libc errno result.
 * Caller-owned oldset is seeded in full, preserving libc's untouched padding.
 */
int64_t thc_signal_mask(int how, const sigset_t *set, sigset_t *oldset,
                        int has_set, int has_oldset, int64_t *error) {
  if (!has_set) set = NULL;
  if (!has_oldset) oldset = NULL;
  *error = 0;
  if (set != NULL && (how == SIG_BLOCK || how == SIG_UNBLOCK || how == SIG_SETMASK)) {
    sigset_t current;
    if (sigprocmask(SIG_BLOCK, NULL, &current) != 0) {
      *error = errno;
      return -1;
    }
    for (int signal = 1; signal < NSIG; ++signal) {
      /* glibc reserves the signals below SIGRTMIN above its kernel realtime
       * minimum (32 on this selected ABI). pthread_sigmask strips them.
       * The kernel also ignores attempts to block SIGKILL and SIGSTOP.
       * No padding or out-of-kernel-range bits participate in the mask.
       */
      if (signal == SIGTTOU || signal == SIGKILL || signal == SIGSTOP ||
          (signal >= __SIGRTMIN && signal < SIGRTMIN)) continue;
      const int before = sigismember(&current, signal);
      const int requested = sigismember(set, signal);
      if (before < 0 || requested < 0) return -2;
      const int after = how == SIG_BLOCK ? before || requested :
                        how == SIG_UNBLOCK ? before && !requested : requested;
      if (before != after) return -2;
    }
  }
  /* set == NULL deliberately ignores how. An invalid how with non-null set
   * goes to libc unchanged and produces its ordinary EINVAL failure. */
  const int result = sigprocmask(how, set, oldset);
  if (result != 0) *error = errno;
  return result;
}
