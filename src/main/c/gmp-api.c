// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <gmp.h>
#include <limits.h>
#include <stdint.h>
#include <math.h>
#include <string.h>

_Static_assert(CHAR_BIT == 8 && sizeof(int) == 4 && sizeof(mp_limb_t) == 8 && sizeof(mp_size_t) == 8 &&
               sizeof(void *) == 8 && GMP_NAIL_BITS == 0,
               "GHC GMP transport requires LP64 and 64-bit no-nails limbs");

int thc_gmp_limb_bits(void) { return mp_bits_per_limb; }

/* All arguments are real native pointers into the caller's confined arena.
 * No managed interop reads, allocation, free or abort occur inside this shim.
 * The host validates complete shapes/aliases and snapshots inputs first; its
 * finally closes the arena even if LLVM interop or result conversion throws. */

/* The wrappers enforce the C scalar result type (notably signed int for cmp).
 * Volatile function pointers keep calls to the actual linked GMP implementation
 * instead of compiling its header's optional inline arithmetic into the shim. */
uint64_t thc_gmp_add(void *output, void const *left, int64_t left_size,
                     void const *right, int64_t right_size) {
    mp_limb_t (*volatile call)(mp_ptr, mp_srcptr, mp_size_t, mp_srcptr, mp_size_t) = &__gmpn_add;
    return call(output, left, left_size, right, right_size);
}
uint64_t thc_gmp_add_word(void *output, void const *input, int64_t size, uint64_t word) {
    mp_limb_t (*volatile call)(mp_ptr, mp_srcptr, mp_size_t, mp_limb_t) = &__gmpn_add_1;
    return call(output, input, size, word);
}
uint64_t thc_gmp_subtract(void *output, void const *left, int64_t left_size,
                          void const *right, int64_t right_size) {
    mp_limb_t (*volatile call)(mp_ptr, mp_srcptr, mp_size_t, mp_srcptr, mp_size_t) = &__gmpn_sub;
    return call(output, left, left_size, right, right_size);
}
int64_t thc_gmp_compare(void const *left, void const *right, int64_t size) {
    int (*volatile call)(mp_srcptr, mp_srcptr, mp_size_t) = &__gmpn_cmp;
    return (int64_t)call(left, right, size);
}

uint64_t thc_gmp_multiply(void *output, void const *left, int64_t left_size,
                         void const *right, int64_t right_size) {
    mp_limb_t (*volatile call)(mp_ptr, mp_srcptr, mp_size_t, mp_srcptr, mp_size_t) = &__gmpn_mul;
    return call(output, left, left_size, right, right_size);
}
uint64_t thc_gmp_multiply_word(void *output, void const *input, int64_t size, uint64_t word) {
    mp_limb_t (*volatile call)(mp_ptr, mp_srcptr, mp_size_t, mp_limb_t) = &__gmpn_mul_1;
    return call(output, input, size, word);
}
uint64_t thc_gmp_divide_word(void *output, int64_t fractional_size,
                            void const *input, int64_t size, uint64_t divisor) {
    mp_limb_t (*volatile call)(mp_ptr, mp_size_t, mp_srcptr, mp_size_t, mp_limb_t) = &__gmpn_divrem_1;
    return call(output, fractional_size, input, size, divisor);
}
uint64_t thc_gmp_modulo_word(void const *input, int64_t size, uint64_t divisor) {
    mp_limb_t (*volatile call)(mp_srcptr, mp_size_t, mp_limb_t) = &__gmpn_mod_1;
    return call(input, size, divisor);
}
void thc_gmp_divide(void *quotient, void *remainder, int64_t fractional_size,
                    void const *numerator, int64_t numerator_size,
                    void const *divisor, int64_t divisor_size) {
    void (*volatile call)(mp_ptr, mp_ptr, mp_size_t, mp_srcptr, mp_size_t, mp_srcptr, mp_size_t) = &__gmpn_tdiv_qr;
    call(quotient, remainder, fractional_size, numerator, numerator_size, divisor, divisor_size);
}

/* GHC's separate quotient/remainder contracts each discard the other result.
 * Unlike the original gmp_wrappers.c stack/malloc scratch policy, all scratch
 * is preallocated by the host arena and released by host finally. These are
 * separate adapters, not ABI aliases for the seven-argument mpn_tdiv_qr. */
void thc_gmp_quotient(void *output, void *remainder_scratch,
                      void const *numerator, int64_t numerator_size,
                      void const *divisor, int64_t divisor_size) {
    thc_gmp_divide(output, remainder_scratch, 0, numerator, numerator_size, divisor, divisor_size);
}
void thc_gmp_remainder(void *output, void *quotient_scratch,
                       void const *numerator, int64_t numerator_size,
                       void const *divisor, int64_t divisor_size) {
    thc_gmp_divide(quotient_scratch, output, 0, numerator, numerator_size, divisor, divisor_size);
}

/* GHC gmp_wrappers.c shift/get_d contracts, adapted from the BSD3-licensed
 * wrappers Copyright (c) 2014 Herbert Valerio Riedel <hvr@gnu.org>.
 * The host checks positive counts, exact capacities and permitted aliases.
 * Scan discarded limbs before writing, including for exact-start aliases. */
uint64_t thc_gmp_shift_right(void *output, void const *input, int64_t size,
                             uint64_t count, int negative) {
    mp_ptr out = output;
    mp_srcptr in = input;
    mp_size_t whole = count / GMP_NUMB_BITS;
    unsigned bits = count % GMP_NUMB_BITS;
    mp_size_t remaining = size - whole;
    int discarded = 0;
    if (negative) {
        for (mp_size_t i = 0; i < whole; ++i) discarded |= in[i] != 0;
        if (bits) discarded |= (in[whole] & ((((mp_limb_t)1) << bits) - 1)) != 0;
    }
    if (bits) {
        mp_limb_t (*volatile call)(mp_ptr, mp_srcptr, mp_size_t, unsigned) = &__gmpn_rshift;
        call(out, in + whole, remaining, bits);
    } else {
        memmove(out, in + whole, remaining * sizeof(mp_limb_t));
        if (negative) out[remaining++] = 0;
    }
    if (discarded) {
        mp_limb_t (*volatile add)(mp_ptr, mp_srcptr, mp_size_t, mp_limb_t) = &__gmpn_add_1;
        add(out, out, remaining, 1);
    }
    return out[remaining - 1];
}

double thc_gmp_get_double(void const *input, int64_t size, int64_t exponent) {
    mp_srcptr limbs = input;
    if (size == 0 || ((size == 1 || size == -1) && limbs[0] == 0)) return 0.0;
    const __mpz_struct value = { ._mp_alloc = 0, ._mp_size = (int)size, ._mp_d = (mp_ptr)limbs };
    if (exponent == 0) return mpz_get_d(&value);
    long scale = 0;
    double fraction = mpz_get_d_2exp(&scale, &value);
    /* Preserve the original LP64 wrapper's narrowing at the C-int ldexp ABI
     * without introducing signed-overflow UB in the intermediate addition. */
    return ldexp(fraction, (int32_t)((uint64_t)scale + (uint64_t)exponent));
}
