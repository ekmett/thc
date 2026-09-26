# Scalar integer completion

Both backends implement the remaining non-vector arithmetic operations:

- `quotRemInt8#`, `quotRemInt16#`, `quotRemInt32#`;
- `quotRemWord8#`, `quotRemWord16#`, `quotRemWord32#`;
- `uncheckedShiftRLInt8#`, `uncheckedShiftRLInt16#`, `uncheckedShiftRLInt32#`;
- `quotRemWord2#` and `mulIntMayOflo#`.

The narrow quotient/remainder operations return quotient first, remainder second,
with signed truncation toward zero or unsigned division according to the operation.
Narrow signed values retain sign-extended Long carriers; unsigned values retain
zero-extended carriers. Logical right shifts zero-fill the selected narrow width,
then restore its signed carrier (so a shift of zero preserves a negative input).
Unchecked shift counts have the GHC domain `0 <= count < width`.

`quotRemWord2# high low divisor` divides the unsigned 128-bit dividend by one
64-bit word. GHC requires unsigned `high < divisor`, which also excludes a zero
divisor. Both backends check that boundary before publishing either result.
A shared restoring-division algorithm uses only Long arithmetic; result slots
receive quotient and remainder directly, without a Pair, BigInteger or scratch
array. No performance equivalence to native hardware division is claimed.

`mulIntMayOflo#` may conservatively report overflow according to GHC. THC returns
the exact 0/1 overflow flag using the signed high half of the product. Native
comparisons check soundness rather than assuming every platform produces that
exact flag. A nonzero native answer need not mean the product actually overflows.
Zero-divisor errors are rejected without partially publishing tuple results.
Signed minimum divided by -1 is excluded from portable native-oracle claims.

These operations unblock fixed-width `Integral` and logical shift implementations
in GHC.Internal.Int/Word and double-word division in GHC's native bignum backend.
For example, `quotRem (minBound + 1 :: Int16) 7` retains the correct signed
remainder; `shiftR (-1 :: Int8) 3` remains arithmetic at the public Bits layer,
whereas the new low-level logical primitive returns 31.

## Verification

`cabal run exe:thc-fixtures --offline -- integer-completion` uses pinned GHC
9.14.1 to export genuine pre-/post-Tidy Core, strictly audits all eleven roots,
and generates 3,373 native rows. The manifest closes source, generated Core,
native binary, requests, output and command/stdout/stderr inventories.
`IntegerCompletionTest` independently checks native results with BigInteger,
all defined 8-bit division inputs, 20,000 additional unsigned double-word
divisions, malformed physical carriers/tuples/arity, first-class rejection and
invalid-domain recovery.

Every native row is exercised on AST and bytecode, with inlining enabled and
disabled. Compilation must leave guest call counters unchanged. Each first and
subsequent checked call must enter exactly one source-proven installed root,
without interpreter fallback or additional handoff allocations; argument and
result pools must be empty afterward. Run both default and dense handoff modes:

```sh
./gradlew test --tests thc.runtime.IntegerCompletionTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --tests thc.runtime.IntegerCompletionTest --rerun
```
