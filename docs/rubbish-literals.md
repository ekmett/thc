# GHC absent-value fillers

Both backends lower scalar `LitRubbish` as a non-bottom, same-representation
value. This follows GHC 9.14.1's `GHC.Types.Literal` Note [Rubbish literals],
not the behavior of `absentError`. Evaluating the filler must allow a surrounding
`DEFAULT` case to continue. Its payload is unspecified and must not be observed.
There is no Haskell source syntax for this literal.

The exporter retains a `rubbish` literal with GHC's exact closed scalar `PrimRep`
as its value string. Erased type applications retain the applied type's carrier
metadata. Both loaders and the auditor reject missing/contradictory physical
representation, aggregate/vector metadata, unknown boxed levity, and rubbish
literal alternatives (which are invalid Core).

Machine and fixed-width signed/unsigned integers use zero `Long` carriers;
`FloatRep` and `DoubleRep` use positive zero; `AddrRep` uses a null managed address.
Lifted and unlifted boxed representations use an inert, program-owned reference.
Known data/function types retain the runtime's checked `DataValue`/`Closure`
carriers. These constants are prepared while loading Core, without invoking a
guest function. This does not grant meaningful behavior to observing or calling
an absent value. No thunk, bottom, or invented RTS operation is substituted.

`thc-fixtures rubbish-literals` reads the pinned original
`GHC.Internal.Event.Manager.hi`, retaining its hash and the six `$wstep`
occurrences. They cover the real `LiftedRep`, `UnliftedRep`, `IntRep`, and
`Int32Rep` cases. The producer applies unchanged original literals to closed
types, and separately uses GHC's `mkLitRubbish` for the ordinary scalar matrix.
GHC Core lint checks each typed continuation; GHC's native compiler executes
147 observations across 21 entries. The oracle observes the continuation's
input-dependent result, never the arbitrary filler payload.

AST and bytecode tests exercise both pre- and post-Tidy exports, interpreted and
on the first call after one compilation following checked interpreter profiling,
in both handoff modes. There are no post-compilation settling calls or retries.
Malformed payload, metadata and pattern controls fail in the auditor and both loaders. Evidence
checks include exact source/artifact inventories and the original interface hash.
This is bounded original-literal evidence, not execution of the entire event
manager or the complete GHC API session.

Aggregate rubbish (including empty/singleton tuples and sums) and vector rubbish
remain explicit unsupported exports. GHC normally unarises aggregate fillers
before code generation; THC does not infer their layout from a scalar register
count. Genuine GHC-typed frontier fixtures retain these rejections.

With the pinned toolchain, inside the usual per-worktree build lease:

```sh
cabal run exe:thc-fixtures --offline -- rubbish-literals
./gradlew --max-workers=2 --continue \
  testDefault --tests thc.runtime.RubbishLiteralTest \
  testDense --tests thc.runtime.RubbishLiteralTest
```
