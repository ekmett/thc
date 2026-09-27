/* SPDX-License-Identifier: MIT
 * SPDX-FileCopyrightText: 2025 rust-works
 * SPDX-FileCopyrightText: 2026 Edward Kmett
 * Copyright (c) 2025 rust-works; Copyright (c) 2026 Edward Kmett.
 * Adapted from John Ky's succinctly, commit
 * 6ee3210413d1f180fd6a93ab30c5bc6aaad29b78: json/simple.rs and
 * json/simd/{avx2,neon}.rs. See LICENSE.succinctly and README.md.
 *
 * The source's quote/escape automaton and SIMD classifiers are retained.
 * Outputs are ephemeral Simple Cursor masks with disjoint open/close planes.
 * Persistence and topology encoding belong to the Haskell producer.
 */
#include "json_index.h"
#include <stddef.h>
#include <string.h>
#include <limits.h>
#if !defined(THC_JSON_SCALAR_ONLY) && (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
#include <immintrin.h>
#define THC_AVX2 1
#endif
#if !defined(THC_JSON_SCALAR_ONLY) && defined(__aarch64__) && defined(__ARM_NEON) && !defined(__AARCH64EB__)
#include <arm_neon.h>
#define THC_NEON 1
#endif

typedef struct {
    unsigned state;
    uint8_t *interest, *opens, *closes;
} writer;
typedef struct { uint32_t quote, slash, open, close, delim; } masks;

int thc_json_backend_available(int backend) {
    switch (backend) {
    case THC_JSON_AUTO: case THC_JSON_SCALAR: return 1;
    case THC_JSON_AVX2:
#ifdef THC_AVX2
        __builtin_cpu_init();
        return __builtin_cpu_supports("avx2") != 0;
#else
        return 0;
#endif
    case THC_JSON_NEON:
#ifdef THC_NEON
        return 1; /* Advanced SIMD is mandatory for this AArch64 target. */
#else
        return 0;
#endif
    default: return 0;
    }
}
int thc_json_backend_selected(int backend) {
    if (backend == THC_JSON_AUTO) {
        if (thc_json_backend_available(THC_JSON_AVX2)) return THC_JSON_AVX2;
        if (thc_json_backend_available(THC_JSON_NEON)) return THC_JSON_NEON;
        return THC_JSON_SCALAR;
    }
    return thc_json_backend_available(backend) ? backend : -1;
}
static void set_bit(uint8_t *bytes, uint64_t bit) {
    bytes[bit >> 3] |= (uint8_t)(1u << (bit & 7u));
}
static void emit(writer *w, uint64_t offset, int kind) {
    set_bit(w->interest, offset);
    if (kind == 1) set_bit(w->opens, offset);
    if (kind == 0) set_bit(w->closes, offset);
}
static void scalar(writer *w, const uint8_t *s, uint64_t begin, uint64_t end) {
    for (uint64_t i = begin; i < end; ++i) {
        uint8_t c = s[i];
        if (w->state == 2) w->state = 1;
        else if (w->state == 1) {
            if (c == '"') w->state = 0;
            else if (c == '\\') w->state = 2;
        } else if (c == '"') w->state = 1;
        else if (c == '{' || c == '[') emit(w, i, 1);
        else if (c == '}' || c == ']') emit(w, i, 0);
        else if (c == ',' || c == ':') emit(w, i, 2);
    }
}
#if defined(THC_AVX2) || defined(THC_NEON)
/* The NEON source skips ordinary string runs using trailing-zero scans.
 * This traversal also skips ordinary non-string runs. Carry state 2
 * consumes exactly one byte even when its backslash was in the previous block. */
static void consume_masks(writer *w, masks m, uint64_t base) {
    unsigned pos = 0;
    while (pos < 32) {
        if (w->state == 2) { w->state = 1; ++pos; continue; }
        uint32_t wanted = w->state == 1 ? (m.quote | m.slash)
            : (m.quote | m.open | m.close | m.delim);
        wanted &= UINT32_MAX << pos;
        if (wanted == 0) return;
        pos = (unsigned)__builtin_ctz(wanted);
        uint32_t bit = UINT32_C(1) << pos;
        if (w->state == 1) w->state = (m.quote & bit) ? 0u : 2u;
        else if (m.quote & bit) w->state = 1;
        else emit(w, base + pos, (m.open & bit) ? 1 : (m.close & bit) ? 0 : 2);
        ++pos;
    }
}
#endif
#ifdef THC_AVX2
__attribute__((target("avx2")))
static masks classify_avx2(const uint8_t *s) {
    __m256i v = _mm256_loadu_si256((const __m256i *)(const void *)s);
    masks m;
    m.quote = (uint32_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, _mm256_set1_epi8('"')));
    m.slash = (uint32_t)_mm256_movemask_epi8(_mm256_cmpeq_epi8(v, _mm256_set1_epi8('\\')));
    m.open = (uint32_t)_mm256_movemask_epi8(_mm256_or_si256(
        _mm256_cmpeq_epi8(v, _mm256_set1_epi8('{')), _mm256_cmpeq_epi8(v, _mm256_set1_epi8('['))));
    m.close = (uint32_t)_mm256_movemask_epi8(_mm256_or_si256(
        _mm256_cmpeq_epi8(v, _mm256_set1_epi8('}')), _mm256_cmpeq_epi8(v, _mm256_set1_epi8(']'))));
    m.delim = (uint32_t)_mm256_movemask_epi8(_mm256_or_si256(
        _mm256_cmpeq_epi8(v, _mm256_set1_epi8(',')), _mm256_cmpeq_epi8(v, _mm256_set1_epi8(':'))));
    return m;
}
#endif
#ifdef THC_NEON
/* Exact nibble products from succinctly. Separate comma/colon planes avoid
 * falsely classifying '*' and '<' as delimiters. */
static const uint8_t lo_table[16] = {0,0,8,0,0,0,0,0,0,0,32,1,20,2,0,0};
static const uint8_t hi_table[16] = {0,0,12,32,0,19,0,3,0,0,0,0,0,0,0,0};
static uint32_t neon_mask(uint8x16_t v) {
    uint64x2_t lanes = vreinterpretq_u64_u8(vshrq_n_u8(v, 7));
    uint64_t a = vgetq_lane_u64(lanes, 0), b = vgetq_lane_u64(lanes, 1);
    return (uint32_t)((a * UINT64_C(0x0102040810204080)) >> 56)
        | (uint32_t)(((b * UINT64_C(0x0102040810204080)) >> 56) << 8);
}
static masks classify_neon16(const uint8_t *s) {
    uint8x16_t v = vld1q_u8(s);
    uint8x16_t classes = vandq_u8(vqtbl1q_u8(vld1q_u8(lo_table), vandq_u8(v, vdupq_n_u8(15))),
                                 vqtbl1q_u8(vld1q_u8(hi_table), vshrq_n_u8(v, 4)));
    masks m;
    m.quote = neon_mask(vtstq_u8(classes, vdupq_n_u8(8)));
    m.slash = neon_mask(vtstq_u8(classes, vdupq_n_u8(16)));
    m.open = neon_mask(vtstq_u8(classes, vdupq_n_u8(1)));
    m.close = neon_mask(vtstq_u8(classes, vdupq_n_u8(2)));
    m.delim = neon_mask(vtstq_u8(classes, vdupq_n_u8(4 | 32)));
    return m;
}
static masks classify_neon(const uint8_t *s) {
    masks a = classify_neon16(s), b = classify_neon16(s + 16);
    masks m = {a.quote | (b.quote << 16), a.slash | (b.slash << 16),
               a.open | (b.open << 16), a.close | (b.close << 16), a.delim | (b.delim << 16)};
    return m;
}
#endif
static void scan(writer *w, const uint8_t *s, uint64_t length, int backend) {
    uint64_t pos = 0;
#ifdef THC_AVX2
    if (backend == THC_JSON_AVX2)
        for (; length - pos >= 32; pos += 32) consume_masks(w, classify_avx2(s + pos), pos);
#endif
#ifdef THC_NEON
    if (backend == THC_JSON_NEON)
        for (; length - pos >= 32; pos += 32) consume_masks(w, classify_neon(s + pos), pos);
#endif
    (void)backend;
    scalar(w, s, pos, length); /* No vector load crosses the supplied end. */
}
int thc_json_scan_block(const uint8_t *source, uint64_t length, int backend,
                        uint32_t initial_state, uint32_t *final_state,
                        uint8_t *interest, uint8_t *opens, uint8_t *closes,
                        uint64_t mask_bytes) {
    if (length > 512 || initial_state > 2 || final_state == NULL
        || mask_bytes != ((length + 63) / 64) * 8
        || (length && (!source || !interest || !opens || !closes))) return THC_JSON_ARGUMENT;
    backend = thc_json_backend_selected(backend);
    if (backend < 0) return THC_JSON_UNSUPPORTED;
    if (mask_bytes) {
        memset(interest, 0, (size_t)mask_bytes);
        memset(opens, 0, (size_t)mask_bytes);
        memset(closes, 0, (size_t)mask_bytes);
    }
    writer w = {0};
    w.state = initial_state;
    w.interest = interest; w.opens = opens; w.closes = closes;
    scan(&w, source, length, backend);
    *final_state = w.state;
    return THC_JSON_OK;
}
