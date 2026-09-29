# Exact empty tuple join inputs

Local GHC joins accept the exact `(# #)` logical argument. The capability is
`empty-unboxed-tuple` entry in `aggregateJoinInputs`, independently of ordinary
function inputs and join results. State tokens, boxed `()`, `(# State# s #)` and
nested empty tuples are different shapes. [Nonempty tuple joins](tuple-joins.md)
use the companion `unboxed-tuple` capability; [binary sum joins](sum-inputs.md) use
`unboxed-sum`. Owned aggregate captures follow the [tuple](tuple-captures.md)
and [sum](sum-inputs.md) contracts.

Logical arity and exact saturation include the empty argument. Both compilers
validate its proof and original unlifted flag, then evaluate its expression in
argument order before transferring control. AST joins invoke its tuple writer
with an empty destination; bytecode emits the same writer without a local.
Neither allocates a formal or scratch payload slot or a boxed Unit for that
argument. Scalar operands are saved before any formals are overwritten, retaining
parallel moves for recursive swaps and mutual joins. AST nodes cache their typed
copy nodes while lowering; an empty shape has no copy fields, and compilation
does not inspect logical-shape lists or retain a scalar write for an empty slot. Joins still use direct
local control flow, without a guest call or input/result carrier for the transfer.
An ordinary effectful producer used as an operand retains its existing call and
result convention.
