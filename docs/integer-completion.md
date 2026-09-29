# Scalar integer completion

Both backends implement the remaining non-vector arithmetic operations:

- `quotRemInt8#`, `quotRemInt16#`, `quotRemInt32#`;
- `quotRemWord8#`, `quotRemWord16#`, `quotRemWord32#`;
- `uncheckedShiftRLInt8#`, `uncheckedShiftRLInt16#`, `uncheckedShiftRLInt32#`;
- `quotRemWord2#` and `mulIntMayOflo#`.

The narrow quotient/remainder operations return quotient first, remainder second,
with signed truncation toward zero or unsigned division according to the operation.
Narrow values compute in `Int`: signed 8/16-bit values are sign-extended,
unsigned 8/16-bit values are zero-extended, and `Word32` retains raw bits.
Logical right shifts zero-fill the selected narrow width,
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
