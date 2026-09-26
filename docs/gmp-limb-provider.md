# Checked native GMP limb provider

This implements fifteen exact original GHC foreign calls: fourteen through a
checked native provider on Linux x86_64, plus the original RTS scalar
`__int_encodeDouble` translated directly to JVM scaling. Genuine pre/post Core calls match a native GHC
oracle in interpreted and first-installed compiled AST/bytecode execution,
with inlining both enabled and disabled. No whole hello or lens load is
established by these bounded tests.

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

The extended original-call fixture has 296 native observations and fifteen
genuine imported declarations. It adds `integer_gmp_mpn_rshift`,
`integer_gmp_mpn_rshift_2c`, `integer_gmp_mpn_get_d` and `__int_encodeDouble`.
Both backends retain typed Double result slots; no boxed numeric result packet
or Java `BigInteger` conversion is introduced. Both handoff modes check every
original entry before and on the first call after explicit compilation.

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

The baseline eleven-call contracts and their initial evidence follow.

The eleven adapter entry points correspond to the nine native GMP operations
and two GHC quotient/remainder wrappers reached by the current hello closure.
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
The adapter has no allocation, abort, managed interop reads or free calls.
Host try/finally closes all native memory even when LLVM calls/conversion fail
or the LLVM context is cancelled. This does not promise interruption of an
arbitrary in-flight native GMP call.

## Evidence and boundaries

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

`OriginalGmpAudit.hs` imports the actual hidden installed GMP module through a
fixture-local registration that exposes only that module; unit identity,
dependencies and installed interface/library paths remain unchanged. No new
FFI declarations, aliases, copied bodies or installed database mutations are
used. Both stock and full-Core GHC 9.14.1 produced the same 92 native rows and
all eleven genuine pre/post FCallIds. The native driver sequences original IO
actions and records every buffer before and after each operation. Pure cmp/mod
imports retain their genuine definitions in the interface closure.

`OriginalGmpTest` compares every native result, input/output byte, permitted
alias and canary across both Core stages, both backends and both inlining
settings. It explicitly compiles each observed target once, then checks the
first compiled entry count and retained target on every subsequent call.
There is no compiled settling/retry or diagnostic-mode admission. Both modes
also exercise malformed genuine metadata through both loaders, native-access
denial and invalid counts without guest stores. `CoreGmpForeignTest` covers
all exact descriptors, stored operand kinds and the load-time null-sentinel
predicate; the auditor independently enforces those shapes before admission.

The native fixture producer's `--require-supported` mode requires all 22
strict entry audits to accept. Its `runtimeVerified:false` field correctly
describes a producer that does not run JVM tests; those results are separate.
CI includes the stock-compatible Linux fixture and a closed receipt allowlist,
never a package DB or arbitrary native intermediates.

Initial compiled tests exposed two partial-evaluation issues: a representation
list comparison inside `LocalRead`, and Kotlin's synthetic enum-switch table
inside the new AST node. The exact predicate is now computed at load time,
and direct enum comparisons preserve constant operand selection. Failures and
Graal diagnostics were retained. A harness-only correction invokes genuine
pure aliases through the normal host dispatcher; original Core is unchanged.
The raw cmp discrepancy above was corrected at the runtime boundary, not by
altering native expectations. Fresh whole-program integration remains separate.
