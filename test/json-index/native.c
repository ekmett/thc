/* SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * Copyright (c) 2026 Edward Kmett. */
#include "json_index.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned tests;
static void bit(uint8_t *p, size_t n) { p[n / 8] |= (uint8_t)(1u << (n % 8)); }
/* Independent byte-at-a-time contract model; no classifier tables or mask
 * traversal from the implementation. It accepts all bytes, not only JSON. */
static unsigned reference(const uint8_t *s, size_t n, unsigned state,
                          uint8_t *interest, uint8_t *opens, uint8_t *closes) {
    for (size_t i = 0; i < n; ++i) {
        if (state == 2) { state = 1; continue; }
        if (state == 1) {
            if (s[i] == '\\') state = 2;
            if (s[i] == '"') state = 0;
            continue;
        }
        switch (s[i]) {
        case '"': state = 1; break;
        case '{': case '[': bit(interest, i); bit(opens, i); break;
        case '}': case ']': bit(interest, i); bit(closes, i); break;
        case ':': case ',': bit(interest, i); break;
        default: break;
        }
    }
    return state;
}
static void check_block(const uint8_t *s, size_t n, unsigned initial) {
    uint8_t expected[3][64] = {{0}};
    unsigned final = reference(s, n, initial, expected[0], expected[1], expected[2]);
    size_t bytes = ((n + 63) / 64) * 8;
    for (int backend = THC_JSON_AUTO; backend <= THC_JSON_NEON; ++backend) {
        uint8_t actual[3][80];
        memset(actual, 0xa5, sizeof actual);
        uint32_t state = 99;
        int status = thc_json_scan_block(s, n, backend, initial, &state,
            actual[0] + 8, actual[1] + 8, actual[2] + 8, bytes);
        if (!thc_json_backend_available(backend)) {
            assert(status == THC_JSON_UNSUPPORTED);
            continue;
        }
        assert(status == THC_JSON_OK && state == final);
        for (unsigned k = 0; k < 3; ++k) {
            assert(memcmp(actual[k] + 8, expected[k], bytes) == 0);
            for (size_t i = 0; i < 8; ++i) assert(actual[k][i] == 0xa5);
            for (size_t i = 8 + bytes; i < 80; ++i) assert(actual[k][i] == 0xa5);
        }
        ++tests;
    }
}
static uint64_t random_state = UINT64_C(0x862593c3a2e77f01);
static uint8_t next_byte(void) {
    random_state ^= random_state << 13;
    random_state ^= random_state >> 7;
    random_state ^= random_state << 17;
    return (uint8_t)random_state;
}
static void check_chain(const uint8_t *s, size_t n, int backend) {
    if (!thc_json_backend_available(backend)) return;
    uint32_t state = 0;
    unsigned expected_state = 0;
    for (size_t start = 0; start < n; start += 512) {
        size_t count = n - start < 512 ? n - start : 512;
        size_t bytes = ((count + 63) / 64) * 8;
        uint8_t expected[3][64] = {{0}}, actual[3][64];
        expected_state = reference(s + start, count, expected_state, expected[0], expected[1], expected[2]);
        assert(thc_json_scan_block(s + start, count, backend, state, &state,
            actual[0], actual[1], actual[2], bytes) == THC_JSON_OK);
        assert(state == expected_state);
        for (unsigned k = 0; k < 3; ++k) assert(memcmp(expected[k], actual[k], bytes) == 0);
        ++tests;
    }
}
#ifdef THC_JSON_TEST_EMBEDDED
int thc_json_test_native(void) {
#else
int main(void) {
#endif
    uint8_t data[4099 + 32];
    static const uint8_t alphabet[] = "\\\"{},:[]abcdef012345 \n\t";
    /* Every byte at every position in a 32-byte classifier block, from each
     * possible carried state. In particular '*' and '<' must not be delimiters. */
    for (unsigned value = 0; value < 256; ++value) {
        for (unsigned position = 0; position < 32; ++position) {
            memset(data, 'x', 64); data[position] = (uint8_t)value;
            for (unsigned state = 0; state < 3; ++state) check_block(data, 64, state);
        }
    }
    /* All tails/alignment offsets, including backslash/quote runs that cross
     * 16/32/64/512 byte boundaries; arbitrary UTF-8 bytes are opaque to lexer. */
    for (unsigned alignment = 0; alignment < 32; ++alignment) {
        for (size_t i = 0; i < sizeof data; ++i)
            data[i] = i % 3 == 0 ? next_byte() : alphabet[next_byte() % (sizeof alphabet - 1)];
        for (size_t n = 0; n <= 512; ++n)
            for (unsigned state = 0; state < 3; ++state) check_block(data + alignment, n, state);
    }
    for (unsigned slashes = 0; slashes < 70; ++slashes) {
        memset(data, 'a', sizeof data); data[0] = '"';
        memset(data + 510, '\\', slashes); data[510 + slashes] = '"';
        memcpy(data + 600, "{:,[}]", 6);
        for (int backend = 0; backend <= THC_JSON_NEON; ++backend) check_chain(data, 4099, backend);
    }
    uint8_t masks[3][64] = {{0}};
    uint32_t state;
    assert(thc_json_scan_block(NULL, 0, THC_JSON_SCALAR, 2, &state, NULL, NULL, NULL, 0) == 0 && state == 2);
    assert(thc_json_scan_block(data, 513, 0, 0, &state, masks[0], masks[1], masks[2], 64) == THC_JSON_ARGUMENT);
    assert(thc_json_scan_block(data, 1, 0, 3, &state, masks[0], masks[1], masks[2], 8) == THC_JSON_ARGUMENT);
    assert(thc_json_scan_block(data, 1, 0, 0, &state, masks[0], masks[1], masks[2], 0) == THC_JSON_ARGUMENT);
    printf("native JSON scanner: %u independent parity checks; scalar=1 avx2=%d neon=%d auto=%d\n",
           tests, thc_json_backend_available(THC_JSON_AVX2), thc_json_backend_available(THC_JSON_NEON),
           thc_json_backend_selected(THC_JSON_AUTO));
    return 0;
}
