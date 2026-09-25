# Checked native GMP limb provider

This is a runtime foundation, **not original GHC foreign-call admission**.
The Core validator, auditor and capability tables remain unchanged. No hello
or lens load is established by these tests.

`LimbRegion` describes the original guest allocation, limb count and writable
status. Limbs are least-significant first, eight bytes each in native byte
order. `LimbProvider` is independent of GMP, Sulong and native allocation;
another implementation can preserve the guest representation and operation
contracts. No operation converts a multi-limb value to `BigInteger`.

The first provider is Linux x86_64 LP64 with 64-bit, no-nails GMP. Clang and
GMP development headers/library are build dependencies on this platform. Other
platforms are rejected by the provider before loading native code. The ordinary
MD5 build remains present on its previously supported platforms.

## Contracts

The eleven adapter entry points correspond to the nine native GMP operations
and two GHC quotient/remainder wrappers reached by the current hello closure.
The [GMP low-level contracts](https://gmplib.org/manual/Low_002dlevel-Functions)
are checked per operation, not with one generic length/overlap rule:

| Operations | Checked shape and overlap |
|---|---|
| add/subtract | Positive inputs, left count >= right count; output left count; only exact-start output/input aliasing |
| add-word | Positive input; equally sized output; exact-start aliasing |
| compare | Equal, positive counts; signed native C-int result widened to machine Int |
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

Every region, output capacity, mutability and alias contract is checked before
the native call. Pointer-bearing managed storage is rejected. Inputs are copied
into native snapshots before any output store. The divisor normalization check
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
The adapter has no allocation, abort, managed interop reads or free calls.
Host try/finally closes all native memory even when LLVM calls/conversion fail
or the LLVM context is cancelled. This does not promise interruption of an
arbitrary in-flight native GMP call.

## Evidence and remaining work

Six `SulongLimbProviderTest` tests exercise all eleven adapter entries using
fixed arithmetic controls, unsigned carry/borrow, aliases, canaries, empty
division cases, malformed shapes/divisors, pointer-cell rejection, immutable
destinations, logical shrink and native-access denial. All six plus four
existing Sulong/MD5 tests pass in default and dense modes. The separate
`bench/experiments/gmp-sulong/run-ownership.sh` passes pointer lifetime,
confinement, failed interop and post-cancellation cleanup controls.

An initial test had an incorrect expected multi-limb division result:
`2*B + 7 = 2*(B + 3) + 1` for `B = 2^64`. Native GMP correctly returned
quotient 2/remainder 1. The retained failure was fixed only in test constants,
and a native GHC arithmetic control independently confirmed `(2,1)`.

These are provider controls, not a native Haskell original-declaration oracle
or first-installed compiled Core evidence. Remaining gates before admission:
exact original FCallId/ABI/representation/State validation, genuine Haskell
pre/post fixtures, typed AST/bytecode lowering and compiled-entry tests, then
fresh strict whole-program audits. No support table is advanced by this slice.
