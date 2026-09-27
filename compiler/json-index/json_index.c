/* SPDX-License-Identifier: MIT AND BSD-2-Clause
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
 * The directory builder below follows Edward Kmett's public Everett rank.h,
 * eaa5ff3ccdb970cd684d8a01fe5fcea2d3bc23ca, under BSD-2-Clause.
 * See LICENSE.everett and LICENSE.everett-bsd.
 */
#include "json_index.h"
#include <stddef.h>
#include <string.h>
#include <limits.h>
#if !defined(THC_JSON_SCALAR_ONLY) && (defined(__x86_64__) || defined(__i386__)) && (defined(__GNUC__) || defined(__clang__))
#include <immintrin.h>
#define THC_AVX2 1
#ifdef _WIN32
#include <cpuid.h>
#endif
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
#ifdef _WIN32
    {
        /* GHC's vanilla Windows plugin loader cannot resolve compiler-rt's
         * __cpu_indicator_init/__cpu_model helpers. Query the same CPU/OS
         * requirements directly, without adding an AVX target to this code. */
        unsigned a, b, c, d;
        const unsigned required = bit_XSAVE | bit_OSXSAVE | bit_AVX;
        if (!__get_cpuid(1, &a, &b, &c, &d) || (c & required) != required) return 0;
        /* OSXSAVE makes XGETBV legal. XMM and YMM state must both be enabled. */
        __asm__ volatile ("xgetbv" : "=a" (a), "=d" (d) : "c" (0));
        if ((a & 6u) != 6u) return 0;
        return __get_cpuid_count(7, 0, &a, &b, &c, &d) && (b & bit_AVX2) != 0;
    }
#else
        __builtin_cpu_init();
        return __builtin_cpu_supports("avx2") != 0;
#endif
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

/* Whole-document passes resolve the ISA once and reuse this bounded scratch.
 * No input-sized interest bitmap or per-quarter allocation is retained. */
static unsigned block_masks(const uint8_t *source, uint64_t length, int backend,
                            unsigned state, uint8_t masks_out[3][64]) {
    memset(masks_out, 0, 3 * 64);
    writer w = {0};
    w.state = state;
    w.interest = masks_out[0]; w.opens = masks_out[1]; w.closes = masks_out[2];
    scan(&w, source, length, backend);
    return w.state;
}
static unsigned population(const uint8_t *bytes, unsigned length) {
    unsigned result = 0;
    for (unsigned i = 0; i < length; ++i) {
        /* Portable byte population; compilers may select their target's legal
         * instructions without imposing a runtime POPCNT requirement. */
        unsigned v = bytes[i];
        v = v - ((v >> 1) & 0x55u);
        v = (v & 0x33u) + ((v >> 2) & 0x33u);
        result += (v + (v >> 4)) & 0x0fu;
    }
    return result;
}
int thc_json_simple_count(const uint8_t *source, uint64_t length, int backend,
                          uint64_t *events, uint32_t *final_state) {
    if (length > SIZE_MAX || (length && !source) || !events || !final_state) return THC_JSON_ARGUMENT;
    backend = thc_json_backend_selected(backend);
    if (backend < 0) return THC_JSON_UNSUPPORTED;
    uint64_t total = 0, offset = 0;
    unsigned state = 0;
    uint8_t scratch[3][64];
    while (offset < length) {
        uint64_t count = length - offset < 512 ? length - offset : 512;
        state = block_masks(source + offset, count, backend, state, scratch);
        total += population(scratch[0], (unsigned)((count + 7) / 8));
        offset += count;
    }
    *events = total; *final_state = state;
    return THC_JSON_OK;
}
static int section_size(uint64_t count, uint64_t per, uint64_t bytes_each, uint64_t *result) {
    uint64_t units = count / per + (count % per != 0);
    if (units > SIZE_MAX / bytes_each) return 0;
    *result = units * bytes_each;
    return 1;
}
static void put32(uint8_t *target, uint32_t value) {
    for (unsigned i = 0; i < 4; ++i) target[i] = (uint8_t)(value >> (8 * i));
}
static void put64(uint8_t *target, uint64_t value) {
    for (unsigned i = 0; i < 8; ++i) target[i] = (uint8_t)(value >> (8 * i));
}
int thc_json_simple_build(const uint8_t *source, uint64_t length, int backend, uint64_t events,
                          uint8_t *epochs, uint64_t epoch_bytes,
                          uint8_t *blocks, uint64_t block_bytes,
                          uint8_t *states, uint64_t state_bytes,
                          uint8_t *bp, uint64_t bp_bytes) {
    if (length > SIZE_MAX || (length && !source) || events > length) return THC_JSON_ARGUMENT;
    backend = thc_json_backend_selected(backend);
    if (backend < 0) return THC_JSON_UNSUPPORTED;
    uint64_t e, b, s, p;
    /* FSM: 2 bits/512 bytes, padded to 64-bit words => one word/16384 bytes.
     * BP: two bits/event => one word/32 events. No overflowing 2*events. */
    if (!section_size(length, UINT64_C(1) << 32, 8, &e)
        || !section_size(length, 2048, 8, &b)
        || !section_size(length, 16384, 8, &s)
        || !section_size(events, 32, 8, &p)
        || e != epoch_bytes || b != block_bytes || s != state_bytes || p != bp_bytes
        || (e && !epochs) || (b && !blocks) || (s && !states) || (p && !bp)) return THC_JSON_ARGUMENT;
    if (e) memset(epochs, 0, (size_t)e);
    if (b) memset(blocks, 0, (size_t)b);
    if (s) memset(states, 0, (size_t)s);
    if (p) memset(bp, 0, (size_t)p);
    uint64_t offset = 0, ordinal = 0, quarter = 0, epoch_base = 0;
    uint32_t packed = 0;
    unsigned state = 0;
    uint8_t scratch[3][64];
    while (offset < length) {
        uint64_t block = quarter >> 2;
        unsigned run = (unsigned)(quarter & 3);
        if (run == 0) {
            /* Everett directory_cursor: one absolute count per 2^32 source
             * bits; here the virtual IB has exactly one bit per JSON byte. */
            if ((block & ((UINT64_C(1) << 21) - 1)) == 0) {
                epoch_base = ordinal;
                put64(epochs + (block >> 21) * 8, ordinal);
            }
            if (ordinal - epoch_base > UINT32_MAX) return THC_JSON_ARGUMENT;
            put32(blocks + block * 8, (uint32_t)(ordinal - epoch_base));
            packed = 0;
        }
        states[quarter >> 2] |= (uint8_t)(state << (2 * run));
        uint64_t count = length - offset < 512 ? length - offset : 512;
        state = block_masks(source + offset, count, backend, state, scratch);
        unsigned found = population(scratch[0], (unsigned)((count + 7) / 8));
        if (run < 3) packed |= (uint32_t)found << (11 * run);
        put32(blocks + block * 8 + 4, packed);
        for (unsigned byte = 0; byte < (count + 7) / 8; ++byte) {
            unsigned interest = scratch[0][byte];
            for (unsigned bit_index = 0; interest; ++bit_index, interest >>= 1) {
                if (!(interest & 1)) continue;
                if (ordinal >= events) return THC_JSON_COUNT_MISMATCH;
                unsigned mask = 1u << bit_index;
                unsigned pair = scratch[1][byte] & mask ? 3u : scratch[2][byte] & mask ? 0u : 2u;
                bp[ordinal >> 2] |= (uint8_t)(pair << (2 * (ordinal & 3)));
                ++ordinal;
            }
        }
        offset += count; ++quarter;
    }
    return ordinal == events ? THC_JSON_OK : THC_JSON_COUNT_MISMATCH;
}
