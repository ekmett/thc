// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
#include <stdint.h>
#ifdef _WIN32
#include <windows.h>
#else
#include <pthread.h>
#endif

void thc_callback_identity_invoke(void (*callback)(void)) { callback(); }
uintptr_t thc_callback_identity_carrier(void) {
#ifdef _WIN32
    return (uintptr_t)GetCurrentThreadId();
#else
    return (uintptr_t)pthread_self();
#endif
}
