# Checked native GMP limb provider

The GMP provider implements the original GHC limb operations on Linux x86_64
LP64 with 64-bit, no-nails GMP. Clang and GMP development headers/libraries are
required. Other platforms are rejected before native loading. The original RTS
scalar `__int_encodeDouble` uses a JVM scaling implementation.

`LimbRegion` describes the original guest allocation, limb count and writable
status. Limbs are least-significant first, eight bytes each in native byte
order. `LimbProvider` is independent of GMP, Sulong and native allocation;
another implementation can preserve the guest representation and operation
contracts. No operation converts a multi-limb value to `BigInteger`.

## Operation contracts

Word GCD preserves unsigned 64-bit values and accepts zero on either side.
Array/word GCD requires a positive limb count and a nonzero word for multi-limb
inputs. Array/array GCD requires positive counts, left count at least right,
normalized multi-limb inputs and output capacity equal to the right count.
It returns the number of result limbs and writes only that prefix, preserving
the unused tail. GHC's caller performs the subsequent logical trim. The native
adapter uses read-only operand views and GMP's own temporary result, like the
original wrapper, rather than destructively applying `mpn_gcd` to guest inputs.

Left shifts require a positive count and an output of
`inputLimbs + ceil(count / 64)` limbs. They preserve low zero limbs and return
the top stored limb, including zero for a partially filled extra limb. Exact
start aliases also work on native-pinned storage without an additional copy.
Logical operations require equal, positive counts and permit exact-start
output/input aliasing. Population count treats every input limb as unsigned;
the input must contain at least one limb. Partial overlap, undersized or
immutable destinations and pointer-bearing storage reject before native stores.

Right shifts require `0 < count < inputLimbs * 64`. The ordinary destination has
`inputLimbs - count / 64` limbs; the negative-magnitude version has
`inputLimbs - (count - 1) / 64` limbs and rounds discarded nonzero bits upward
to implement arithmetic shifting of a negative integer. Both return the top
stored limb, not the shifted-out bits. Invalid capacities and partial overlaps
reject before stores. Exact-start aliases, whole-limb boundaries and the extra
negative carry limb are tested, including native-pinned storage without a copy.

The original `get_d` conversion uses native GMP's truncation toward zero and
retains the wrapper's signed limb count, zero handling and C-int `ldexp`
exponent boundary. `__int_encodeDouble` clamps the exponent to C int, scales
with `Math.scalb`, and preserves the pinned RTS's wrapped machine-absolute-value
and subsequent sign restoration. In particular, `minBound :: Int` produces the
same positive result/sign as that original RTS wrapper, including underflow;
the native oracle records this edge case rather than substituting mathematical
signed conversion. These are original FFI contracts, not new primops.

The [GMP low-level contracts](https://gmplib.org/manual/Low_002dlevel-Functions)
are checked per operation, not with one generic length/overlap rule:

| Operations | Checked shape and overlap |
|---|---|
| add/subtract | Positive inputs, left count >= right count; output left count; only exact-start output/input aliasing |
| add-word | Positive input; equally sized output; exact-start aliasing |
| compare | Equal, positive counts; provider returns the signed native C-int comparison |
| multiply | Positive inputs, left >= right; output left + right; no output/input overlap |
| multiply-word | Positive input; equally sized output; overlapping destination may start at or below input |
| divide-word | Nonzero divisor; nonnegative input/fraction counts, including zero; output their sum; exact-start aliasing |
| modulo-word | Nonzero divisor; input may be empty |
| quotient/remainder | Positive divisor and numerator, numerator >= divisor, nonzero top divisor limb; exact quotient and remainder capacities |

Multi-limb division requires zero fractional limbs. Its arguments are disjoint
except that remainder may start at numerator. Separate quotient-only and
remainder-only adapters allocate the correctly sized discarded output in the
same host arena; they are not ABI aliases for `mpn_tdiv_qr`. These preserve
the behavior of GHC 9.14.1's separate `integer_gmp_mpn_tdiv_q/r` wrappers while
replacing their stack/malloc scratch policy with host-owned lifetime.

The original `c_mpn_cmp` import declares an `Int#` result for GMP's C `int`.
The native oracle on the verified x86_64 target observes `4294967295` for a
negative comparison, not `-1`; GHC's original `bignat_compare` applies
`narrowCInt#` afterwards. `ManagedGmp` therefore zero-extends the provider's
low 32 bits only at this original FCall boundary. The provider contract stays
signed. This is a bounded native-observed ABI detail, not a claim about upper
return-register bits on other targets.

Every region, output capacity, mutability and alias contract is checked before
the native call. Pointer-bearing managed storage is rejected. Inputs are copied
into native snapshots before any output store, unless the region is already
native-pinned: such regions borrow their existing address for the call and do
not allocate a second data buffer. The divisor normalization check
uses that actual snapshot, so subsequent guest mutation cannot supply a zero
divisor to the native routine. Copies recheck logical allocation size under the
allocation-owner monitor; concurrent guest data races are not made transactional.

## Native lifetime and ABI

`NativeLimbScope` owns a confined Java 25 arena with eight-byte aligned native
allocations. The private Truffle pointer wrapper retains its segment and refuses
expired or cross-thread access. Neither JVM buffer addresses nor process pointers
are exposed as guest `Addr#`. Zero-length logical regions have an aligned native
sentinel allocation but zero copy capacity.

Sulong interprets an embedded LLVM adapter with a real native GMP dependency.
Volatile C function pointers retain actual external GMP calls, including those
whose headers offer inline implementations. No arithmetic is emulated in Java.
The adapter has no managed interop reads. Multi-limb GCD uses GMP-owned temporary
result storage and frees it before returning, as the original GHC wrapper does;
the other adapters introduce no native allocation or free calls.
Host try/finally closes all native memory even when LLVM calls/conversion fail
or the LLVM context is cancelled. This does not promise interruption of an
arbitrary in-flight native GMP call.
