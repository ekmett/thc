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
`GHC=/path/to/ghc-9.14.1 cabal run exe:thc-primops -- scalars --write`.
Normal test preparation runs the generator without `--write` and requires an
exact match. The generator queries `primOpSig` and `typePrimRep_maybe` through the
GHC API, after structurally excluding quantified and non-primitive types; it does
not parse pretty-printed type signatures. It checks the 64-bit target and retains
compiler information, the tool invocation/binary hash and Haskell source/input hashes in
`build/scalar-signatures/provenance.json`.

The table covers 362 monomorphic scalar operations with primitive arguments and
results. It is a tooling contract, not additional primitive support or a claim
about undefined numeric inputs. Runtime tuple/vector transport, actual JVM
carriers, bounds, ownership, lifetime and aliasing retain their own necessary
checks; removing redundant scalar type validation does not remove those checks.

The auditor checks both occurrence and stored binder proofs. Missing or explicitly unknown metadata remains conservative, while intrinsic
literals retain their exact representation when its kind establishes one. Evaluatedness
is not a type constraint, and legal scalar newtype casts preserve their primitive
representation.
