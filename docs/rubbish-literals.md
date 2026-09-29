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

Machine and fixed-width signed/unsigned integers use zero integral carriers;
`FloatRep` and `DoubleRep` use positive zero; `AddrRep` uses a null managed address.
Lifted and unlifted boxed representations use an inert, program-owned reference.
Known data/function types retain the runtime's checked `DataValue`/`Closure`
carriers. These constants are prepared while loading Core, without invoking a
guest function. This does not grant meaningful behavior to observing or calling
an absent value. No thunk, bottom, or invented RTS operation is substituted.

Aggregate rubbish (including empty/singleton tuples and sums) and vector rubbish
remain explicit unsupported exports. GHC normally unarises aggregate fillers
before code generation; THC does not infer their layout from a scalar register
count. Genuine GHC-typed frontier fixtures retain these rejections.
