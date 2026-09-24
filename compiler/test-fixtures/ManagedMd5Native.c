/* Test driver for the original public-domain Colin Plumb/GHC MD5 source.
 * Link the pinned md5.c unchanged; this file is an ABI oracle, not an MD5 port. */
#include "md5.h"
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <limits.h>

_Static_assert(CHAR_BIT == 8, "eight-bit bytes required");
_Static_assert(sizeof(int) == 4, "32-bit CInt required");
_Static_assert(sizeof(struct MD5Context) == 88, "context size");
_Static_assert(_Alignof(struct MD5Context) == 4, "context alignment");
_Static_assert(offsetof(struct MD5Context, buf) == 0, "buf offset");
_Static_assert(offsetof(struct MD5Context, bytes) == 16, "count offset");
_Static_assert(offsetof(struct MD5Context, in) == 24, "scratch offset");

static void hex(const unsigned char *p, size_t count) {
    for (size_t i = 0; i < count; ++i) printf("%02x", p[i]);
}

static void row(int id, int length, int split, int seed, int co, int io, int oo,
                uint32_t low, uint32_t high, int phase,
                const unsigned char context[104], const unsigned char output[32]) {
    printf("case\t%d\t%d\t%d\t%d\t%d\t%d\t%d\t%u\t%u\t%d\t",
           id, length, split, seed, co, io, oo, low, high, phase);
    hex(context, 104); putchar('\t'); hex(output, 32); putchar('\n');
}

static void run(int id, int length, int split, int seed, uint32_t low, uint32_t high) {
    /* Malloc storage acquires the MD5Context effective type through its writes. */
    unsigned char *context = malloc(104);
    unsigned char *input = malloc((size_t)length + 16);
    unsigned char *before = malloc((size_t)length + 16);
    unsigned char output[32];
    if (!context || !input || !before) exit(2);
    const int co = (id % 3) * 4, io = (id % 3 == 0 ? 0 : id % 3 == 1 ? 1 : 7);
    const int oo = (id % 3 == 0 ? 0 : id % 3 == 1 ? 3 : 11);
    memset(context, 0xa5, 104); memset(input, 0x6b, (size_t)length + 16);
    memset(output, 0xd3, sizeof(output));
    for (int i = 0; i < length; ++i) input[io + i] = (unsigned char)(i * 73 + seed * 19 + (i >> 3));
    memcpy(before, input, (size_t)length + 16);
    struct MD5Context *ctx = (struct MD5Context *)(void *)(context + co);
    __hsbase_MD5Init(ctx);
    ctx->bytes[0] = low; ctx->bytes[1] = high;
    row(id, length, split, seed, co, io, oo, low, high, 0, context, output);
    __hsbase_MD5Update(ctx, input + io, split);
    row(id, length, split, seed, co, io, oo, low, high, 1, context, output);
    __hsbase_MD5Update(ctx, input + io + split, length - split);
    row(id, length, split, seed, co, io, oo, low, high, 2, context, output);
    __hsbase_MD5Final(output + oo, ctx);
    row(id, length, split, seed, co, io, oo, low, high, 3, context, output);
    if (memcmp(before, input, (size_t)length + 16) != 0) exit(3);
    free(before); free(input); free(context);
}

static void alias_row(int id, int input_offset, int length, int output_offset, int phase,
                      const unsigned char *backing) {
    printf("alias\t%d\t%d\t%d\t%d\t%d\t", id, input_offset, length, output_offset, phase);
    hex(backing, 256); putchar('\n');
}

static void aliases(void) {
    const int inputs[] = {32, 48, 112, 128, 128};
    const int lengths[] = {16, 8, 8, 65, 65};
    const int outputs[] = {160, 160, 160, 56, 112};
    for (int id = 0; id < 5; ++id) {
        unsigned char *backing = malloc(256);
        if (!backing) exit(2);
        for (int i = 0; i < 256; ++i) backing[i] = (unsigned char)(i * 17 + 0x35);
        struct MD5Context *ctx = (struct MD5Context *)(void *)(backing + 32);
        __hsbase_MD5Init(ctx);
        alias_row(id, inputs[id], lengths[id], outputs[id], 0, backing);
        __hsbase_MD5Update(ctx, backing + inputs[id], lengths[id]);
        alias_row(id, inputs[id], lengths[id], outputs[id], 1, backing);
        __hsbase_MD5Final(backing + outputs[id], ctx);
        alias_row(id, inputs[id], lengths[id], outputs[id], 2, backing);
        free(backing);
    }
}

int main(void) {
    uint32_t order = 1;
    printf("layout\t88\t0\t16\t24\t4\t%s\n", *(unsigned char *)&order ? "little" : "big");
    const int lengths[] = {0, 1, 2, 7, 15, 16, 31, 55, 56, 57, 63, 64, 65,
                           95, 119, 120, 127, 128, 129, 255, 256, 257, 1024};
    const int seeds[] = {0, 1, 127, 255};
    int id = 0;
    for (size_t l = 0; l < sizeof(lengths) / sizeof(*lengths); ++l) {
        const int length = lengths[l];
        const int candidates[] = {0, 1, length / 2, 55, 56, 63, 64, length};
        for (int s = 0; s < 8; ++s) {
            const int split = candidates[s] < length ? candidates[s] : length;
            int duplicate = 0;
            for (int p = 0; p < s; ++p)
                if ((candidates[p] < length ? candidates[p] : length) == split) duplicate = 1;
            if (duplicate) continue;
            for (size_t seed = 0; seed < sizeof(seeds) / sizeof(*seeds); ++seed)
                run(id++, length, split, seeds[seed], 0, 0);
        }
    }
    const int carry_lengths[] = {17, 64, 129};
    for (int high = 0; high < 2; ++high) for (int l = 0; l < 3; ++l)
        for (int split = 0; split <= 16; split += 8)
            run(id++, carry_lengths[l], split, 127, UINT32_C(0xfffffff0), high ? UINT32_MAX : 0);
    aliases();
    return 0;
}
