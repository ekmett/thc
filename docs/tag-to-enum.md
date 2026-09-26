# Concrete `tagToEnum#` families

The saturated `tagToEnum# @T tag` path retains `T` before Core type erasure. The supported result is a concrete, ordinary enumeration with no type parameters and at least one nullary constructor. GHC's `isEnumerationTyCon` and each original constructor's representation arity establish this property. Newtypes, unresolved or parameterized types, data-family instances, and constructors with fields remain unsupported.

The application carries `enumFamily: {typeConstructor, constructors}`. Both names are full exported identities; the constructor list preserves GHC's original order. Every constructor record repeats that descriptor. The exporter adds the entire family to `exprCons`, including imported constructors absent from the expression tree. Both backends and the auditor check all records, one-based constructor tags, zero fields, and exact `IntRep` operand and lifted data result proofs. Pretty names and the shared reference representation cannot establish an enum family. The descriptor is the retained compile-time nominal proof; consistency checks do not reconstruct an erased Haskell type from its JVM class.

The `primop-coverage.py` inventory includes this separately gated `tagToEnum` capability as an implementation. The [behavior reference](primop-behavior.md#enumeration-constructors) records the valid enumeration families still excluded. The native fixture, strict auditor and runtime tests establish the coverage described here.

Only exactly saturated primitive applications are accepted. `tagToEnum#` is deliberately absent from the generic primitive arity table: a bare primitive, primitive PAP, missing descriptor, or contradictory family is rejected. Ordinary function PAPs containing a saturated primitive body still work. Lifted enum applications retain normal thunk laziness.

The AST evaluates the tag through `executeRequiredLong` and provides a typed `executeDataValue` override. The bytecode operation takes a primitive `long` and returns `DataValue`. Selection returns the same class-owned zero-field values used by ordinary construction, with no per-call wrapper or payload array. A full-width signed range check precedes conversion to an array index. THC reports `RuntimeFault` for negative or out-of-family tags, including large values that would narrow to a valid index. Invalid native GHC tags are not an oracle.

`compiler/test-fixtures/TagToEnumAudit.hs` uses genuine Bool, Ordering, local Colour, and imported External families, plus an ordinary PAP, an unused bottom-containing application and an unused dynamic enum application, and an explicitly threaded State/mutable-variable effect. Fresh native results are checked against independent constructor score tables and signed wrap arithmetic. Pre/post exports and strict audits are retained under `build/tag-to-enum/`, with source/tool/artifact hashes in `provenance.json`. Runtime tests exercise both backends with normal and disabled inlining, strict per-row compiled entry and active-target checks, exact one-entry direct enum roots, invalid full-width tags, malformed metadata, and typed single evaluation/exception behavior. The same tests run under the existing optional handoff mode.

Pinned GHC 9.14.1 sources used for the contract are `GHC.Tc.Gen.App` (Note `[tagToEnum#]`), `GHC.Core.TyCon` (`isEnumerationTyCon`), `GHC.Core.Opt.ConstantFold` (`tagToEnumRule`, comparing `dataConTagZ`), and `GHC.StgToCmm.Prim` (`TagToEnumOp`). GHC uses zero-based primitive tags, while exported constructor-table tags remain one-based.

Reproduce with the pinned GHC and Graal toolchains:

```sh
compiler/build.sh
python3 scripts/prepare-tag-to-enum-audit.py
python3 scripts/test-core-enums.py
./gradlew test --tests thc.runtime.TagToEnumTest
JAVA_TOOL_OPTIONS=-Dthc.handoffSlabs=true ./gradlew test --tests thc.runtime.TagToEnumTest --rerun
```

This fixture removed one `Typeable.sameTypeRep` frontier; it does not by itself establish the complete original array ErrorCall path. The separate `dataToTagSmall#` and `dataToTagLarge#` implementations now cover constructor-to-tag operations, including parameterized algebraic families.
