// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

#include <gmp.h>
#include <stdint.h>
#include <stdlib.h>

_Static_assert(sizeof(mp_limb_t) == 8 && sizeof(mp_size_t) == 8 && GMP_NAIL_BITS == 0,
               "The probe requires GHC's 64-bit no-nails limb ABI");

/* Transport experiment only, not an admitted THC FFI implementation. The
 * caller supplies validated managed byte buffers. Only malloc-backed native
 * storage crosses the external GMP call, never either managed pointer. */
uint64_t thc_probe_gmp_add_1(unsigned char *destination, int64_t destination_offset,
                            unsigned char const *source, int64_t source_offset,
                            int64_t limbs, uint64_t word) {
    if (limbs <= 0 || limbs > 1024 || source_offset < 0 || destination_offset < 0)
        abort();
    size_t bytes = (size_t)limbs * sizeof(mp_limb_t);
    mp_limb_t *native = malloc(bytes * 2);
    if (!native) abort();
    unsigned char *input_bytes = (unsigned char *)native;
    for (size_t i = 0; i < bytes; ++i) input_bytes[i] = source[source_offset + i];
    mp_limb_t *output = native + limbs;
    /* Force the real external symbol even when gmp.h offers an inline body. */
    mp_limb_t (*volatile operation)(mp_ptr, mp_srcptr, mp_size_t, mp_limb_t) = &__gmpn_add_1;
    mp_limb_t carry = operation(output, native, limbs, word);
    unsigned char const *output_bytes = (unsigned char const *)output;
    for (size_t i = 0; i < bytes; ++i) destination[destination_offset + i] = output_bytes[i];
    free(native);
    return carry;
}
