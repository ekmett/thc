# Exact empty tuple join inputs

Local GHC joins accept the exact `(# #)` logical argument. The capability is
`aggregateJoinInputs: ["empty-unboxed-tuple"]`, independently of ordinary
function inputs and join results. State tokens, boxed `()`, `(# State# s #)` and
nested empty tuples are different shapes. Nonempty aggregate join arguments and
aggregate captures remain unsupported.

Logical arity and exact saturation include the empty argument. Both compilers
validate its proof and original unlifted flag, then evaluate its expression in
argument order before transferring control. AST joins invoke its tuple writer
with an empty destination; bytecode emits the same writer without a local.
Neither allocates a formal or scratch payload slot or a boxed Unit for that
argument. Scalar operands are saved before any formals are overwritten, retaining
parallel moves for recursive swaps and mutual joins. AST nodes cache the exact
empty-position mask while lowering, so compilation does not inspect ordinary
logical-shape lists or retain an impossible scalar write for an empty slot. Joins still use direct
local control flow, without a guest call or input/result carrier for the transfer.
An ordinary effectful producer used as an operand retains its existing call and
result convention.

`prepare-empty-join-input.py` compiles a genuine GHC fixture before and after Tidy
and compares 58 native results with independent wrapping arithmetic models. Its
retained joins cover scalar swaps, mutual recursion, ancestor transfers, lazy
lifted neighbors and tuple results; empty producers write a byte or throw.
Runtime tests exercise both backends, inlined and residual calls, exact per-row
guest entry counts, active-target identity and installed validity. Synthetic
checks cover zero physical locals, operand order, failure before transfer and
malformed logical boundaries.

The full retained Typeable exports in
[`compiler/test-fixtures/empty-join-typeable`](../compiler/test-fixtures/empty-join-typeable/README.md)
show six empty join formals and 34 calls becoming representable. They still have
28 missing globals and fail strict auditing; this change does not claim complete
Typeable or exception-library coverage.
