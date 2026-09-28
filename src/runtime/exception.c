// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
// Native GHC does not produce THC foreign objects; metadata is unavailable.
long long thc_exception_v1_text(void *exception, int selector, long long index) {
    (void)exception; (void)selector; (void)index; return -1;
}
