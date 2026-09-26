# Exact scalar primitive signatures

THC trusts GHC's scalar primitive types during runtime lowering. The AST and
bytecode backends do not reload a signature table or duplicate GHC's scalar type
checker. Shared physical carriers such as Long do not redefine GHC's types; they
are the representation used to execute already typed Core.

The standalone static auditor still compares present exact scalar argument and
result proofs with the pinned GHC 9.14.1 signature table. For example, changing
both a binder and its occurrence from `Int64Rep` to `Word64Rep` does not make them
valid auditor inputs to `plusInt64#`. This is an export/fixture diagnostic, not a
runtime admission or execution guarantee for forged Core.

The canonical resource is
`src/main/resources/thc/scalar-primop-signatures.json`, retained in the source tree
for fixture generation and read directly by the Python auditor. It is excluded
from runtime resources and the runtime jar. Generate it with
`GHC=/path/to/ghc-9.14.1 python3 scripts/generate-scalar-signatures.py --write`.
Normal test preparation runs the generator without `--write` and requires an
exact match. The generator queries `primOpSig` and `typePrimRep_maybe` through the
GHC API, after structurally excluding quantified and non-primitive types; it does
not parse pretty-printed type signatures. It checks the 64-bit target and retains
compiler information, the complete query and input hashes in
`build/scalar-signatures/provenance.json`.

The table covers 333 monomorphic scalar operations with primitive arguments and
results. It is a tooling contract, not additional primitive support or a claim
about undefined numeric inputs. Runtime tuple/vector transport, actual JVM
carriers, bounds, ownership, lifetime and aliasing retain their own necessary
checks; removing redundant scalar type validation does not remove those checks.

The auditor checks both occurrence and stored binder proofs. Missing and
explicitly unknown legacy metadata remain compatible with runtime lowering;
intrinsic literal carriers do not invent an exact register proof. Evaluatedness
is not a type constraint, and legal scalar newtype casts preserve their primitive
representation.

Runtime tests retain legacy metadata compatibility and native pre/post-Tidy
newtype arithmetic and conversion controls, including installed compiled-entry
checks on both backends. They no longer require rejection of forged same-carrier
scalar type annotations. Existing native scalar conformance fixtures continue to
verify defined-input numeric semantics independently.
