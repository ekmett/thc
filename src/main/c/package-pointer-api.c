// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

/* Let Sulong retain its allocation identity and byte offset together. This is
 * an interop boundary, not a native copy or a numerical JVM address. */
#include <stdint.h>
#include <string.h>

void *thc_package_pointer_offset(void *base, long long offset) {
    return (unsigned char *) base + offset;
}

/* Transport regression fixture: C owns this storage; no incoming managed
 * argument can supply backing or bounds for the returned pointer. */
void *thc_package_pointer_test_buffer(void) {
    static unsigned char buffer[32];
    return buffer;
}

int thc_package_pointer_equal(void *left, void *right) { return left == right; }
int thc_package_pointer_compare(void *left, void *right) {
    return left < right ? -1 : left > right;
}
long long thc_package_pointer_difference(void *left, void *right) {
    return (unsigned char *) left - (unsigned char *) right;
}
int thc_package_pointer_overlap(void *left, uint64_t left_count, void *right, uint64_t right_count) {
    unsigned char *a = left, *b = right;
    return a < b + right_count && b < a + left_count;
}

/* The caller supplies the operation's width/count, not an alleged allocation
 * extent. As with C Ptr operations, the C program owns external lifetimes. */
uint64_t thc_package_pointer_read(void *base, long long offset, int width) {
    unsigned char *p = (unsigned char *) base + offset;
    switch (width) {
    case 1: return *p;
    case 2: { uint16_t v; memcpy(&v, p, 2); return v; }
    case 4: { uint32_t v; memcpy(&v, p, 4); return v; }
    case 8: { uint64_t v; memcpy(&v, p, 8); return v; }
    default: __builtin_trap();
    }
}

void thc_package_pointer_write(void *base, long long offset, int width, uint64_t value) {
    unsigned char *p = (unsigned char *) base + offset;
    switch (width) {
    case 1: *p = (unsigned char) value; break;
    case 2: { uint16_t v = value; memcpy(p, &v, 2); break; }
    case 4: { uint32_t v = value; memcpy(p, &v, 4); break; }
    case 8: memcpy(p, &value, 8); break;
    default: __builtin_trap();
    }
}

void *thc_package_pointer_read_address(void *base, long long offset) {
    void *value;
    memcpy(&value, (unsigned char *) base + offset, sizeof(value));
    return value;
}

void thc_package_pointer_write_address(void *base, long long offset, void *value) {
    memcpy((unsigned char *) base + offset, &value, sizeof(value));
}

uint64_t thc_package_pointer_strlen(void *base) {
    unsigned char *p = base;
    uint64_t length = 0;
    while (p[length] != 0) ++length;
    return length;
}

void thc_package_pointer_copy(void *target, void *source, uint64_t count) {
    memmove(target, source, count);
}

void thc_package_pointer_fill(void *target, uint64_t count, int value) {
    memset(target, value, count);
}
