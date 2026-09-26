// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

/* Let Sulong retain its allocation identity and byte offset together. This is
 * an interop boundary, not a native copy or a numerical JVM address. */
void *thc_package_pointer_offset(void *base, long long offset) {
    return (unsigned char *) base + offset;
}
