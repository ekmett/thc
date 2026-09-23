# Exact scalar primitive signatures

Both runtimes and the static auditor compare present exact scalar argument and
result proofs with a single GHC 9.14.1 signature table. For example, changing both
a binder and its occurrence from `Int64Rep` to `Word64Rep` no longer makes them
valid operands of `plusInt64#`. Changing the application and enclosing lambda
result together is also rejected. The shared Long carrier does not erase these
GHC type distinctions.

The canonical resource is
`src/main/resources/thc/scalar-primop-signatures.json`, packaged unchanged in the
runtime jar and read directly by the Python auditor. Generate it with
`GHC=/path/to/ghc-9.14.1 python3 scripts/generate-scalar-signatures.py --write`.
Normal test preparation runs the generator without `--write` and requires an
exact match. The generator queries `primOpSig` and `typePrimRep_maybe` through the
GHC API, after structurally excluding quantified and non-primitive types; it does
not parse pretty-printed type signatures. It checks the 64-bit target and retains
compiler information, the complete query and input hashes in
`build/scalar-signatures/provenance.json`.

The table covers 240 currently advertised monomorphic scalar operations with
primitive arguments and results. Tuple, vector and polymorphic operations keep
their existing validation. This table is a type-proof contract, not additional
primitive support or a claim about undefined numeric inputs.

Validation happens only during application lowering. Both backends check the
compiled operand proofs, so an omitted variable occurrence proof cannot hide a
contradictory lexical binder. The auditor likewise checks occurrence and stored
binder proofs. Missing and explicitly unknown legacy metadata remain compatible;
intrinsic literal carriers do not invent an exact register proof. Evaluatedness
is not a type constraint, and legal scalar newtype casts preserve their primitive
representation. No validation runs in guest execution.

Tests mutate genuine exported arithmetic, comparison and conversion applications,
including omitted/unknown occurrences, in strict and diagnostic modes on both
backends. Native pre/post-Tidy newtype arithmetic and conversion controls retain
installed compiled entry checks. Existing native scalar conformance fixtures
continue to verify defined-input numeric semantics independently.
