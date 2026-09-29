# Managed MutVar and ordinary STRef

`newMutVar#`, `readMutVar#`, and `writeMutVar#` lower to one managed reference
with a mutable JVM object field. Ordinary `Control.Monad.ST` and `Data.STRef`
code supplies the control flow; there is no STRef library builtin.

The contracts come from GHC 9.14.1's pinned
[primop declarations](https://gitlab.haskell.org/ghc/ghc/-/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Builtin/primops.txt.pp):

| Primitive | Arguments | Result |
| --- | --- | --- |
| `newMutVar#` | `a_levpoly`, `State# s` | `(# State# s, MutVar# s a_levpoly #)` |
| `readMutVar#` | `MutVar# s a_levpoly`, `State# s` | `(# State# s, a_levpoly #)` |
| `writeMutVar#` | `MutVar# s a_levpoly`, `a_levpoly`, `State# s` | `State# s` |

Here `a_levpoly` is boxed with either known levity. It is not an arbitrary
runtime representation. The cell is `BoxedRep (Just Unlifted)`, and `State#`
is a logical scalar with no physical fields. The two tuple-producing operations
write their single physical reference directly into the caller's typed local
destination on AST and bytecode. They create no tuple carrier or result slab.

Reads return the stored reference unchanged. Storing and reading a lifted
payload do not evaluate it; replacing a bottom or closure preserves that
laziness. An unlifted boxed payload is evaluated according to its exact argument
contract, while its lifted fields remain lazy. The field is neither final nor
`CompilationFinal`, so aliases observe writes and previously read values keep
their original identity. Every operation evaluates and validates its State
operand before allocation, read publication, or mutation.

Public `Eq (STRef s a)` compares cell identity, independent of the payload or
its current value. GHC 9.14.1's [instance](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/ghc-internal/src/GHC/Internal/STRef.hs#L61)
calls `sameMutVar#`. That name is a
[library specialization](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/ghc-internal/src/GHC/Internal/Prim/PtrEq.hs#L119)
of `unsafePtrEquality#`, which calls the existing `reallyUnsafePtrEquality#`
primop. Genuine exported Core therefore uses the existing AST and bytecode
reference-identity operation; no extra primitive alias or STRef builtin is added.
Its two `MutVar#` arguments are unlifted boxed references and its `Int#` result
is a full-width long. Comparing aliases returns true, while distinct cells
with the same contents compare false. Equality does not force either payload.

Both loaders and the static auditor require the exact boxed levity, scalar
State, logical tuple layout, arity and saturated application. Partial and
first-class primitives, unknown representations and FFI references remain outside
this slice. Atomic swap and pair-returning modification use the same cell;
[boxed CAS and lazy value modification](boxed-cas.md) cover `casMutVar#` and
`atomicModifyMutVar_#`, including contended runtime controls.

## Atomic exchange and lazy pair modification

`atomicSwapMutVar#` performs one JVM atomic exchange and returns the previous
reference in `(# state, old #)`. A lifted replacement and the returned payload
remain lazy. The exchange shares the volatile field used by ordinary reads and
writes; it does not copy the cell or compare payload values.

`atomicModifyMutVar2#` atomically installs a lazy first-field selector over one
shared application `result = f old`, returning `(# state, old, result #)`.
The update does not call the modifier. Later demand shares its result or failure;
the selector reads the first lifted field of the returned data record.
Contended retries can allocate discarded thunks but do not execute their
modifiers. The operation requires a lifted function and lifted old/result
payloads, not an arbitrary unboxed result shape.
