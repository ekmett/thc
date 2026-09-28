# Boxed Int and Char cache

`-Dthc.boxedValueCache=true` enables an optional cache of small Haskell
constructor values. It is off by default. Set the property before loading the
program: each constructor layout fixes its cache choice when it is created.
Changing the property does not retrofit an existing layout.

## Exact constructor scope

The cache accepts only these pinned GHC constructor identities and payloads:

| Constructor identity | Primitive payload | Inclusive range |
|---|---|---|
| `ghc-internal:GHC.Internal.Types.I#` | `IntRep` | −16…255 |
| `ghc-internal:GHC.Internal.Types.C#` | `WordRep` | 0…255 |

The constructor must have exactly one non-aggregate field, the matching
constructor name and the exact representation above. A user-defined lookalike,
a different numeric representation or a different constructor is not cached.
Out-of-range Int and Char payloads keep their full-width ordinary behavior.
The cache does not introduce a Unicode validation rule.

[`DataLayout`](../src/main/java/thc/runtime/DataLayout.java) owns its immutable
table. Each cached object is fully initialized through that layout's private
allocation path before publication. Distinct layouts and contexts do not share
cached objects; array-based static storage also keeps separate backing storage.
Fresh allocation and later field initialization remain separate from cache lookup,
so initializing a new value cannot mutate a cached one.

Both backends use the same layout cache. Constructor construction still evaluates
the primitive field expression once. Lazy lifted fields and thunk forcing are
unchanged. This is Haskell `I#`/`C#` value reuse, not a change to the
[`Object[] → Object` call ABI](call-boundaries.md) or to JVM `Long` boxing.

## Checks and cost model

[`BoxedValueCacheTest`](../src/test/java/thc/runtime/BoxedValueCacheTest.java)
checks genuine exported constructor identities, inclusive boundaries, full-width
payloads, lookalike rejection, default-off behavior, layout/context isolation,
field-based and array-based storage, and compiled transitions between cached
and uncached construction on both backends.

A dynamic cache hit can avoid a constructor allocation, but the range check and
table load have their own cost. Source inspection alone does not establish that
the compiled path allocates less or runs faster. Keep the runtime, exported Core,
JVM configuration and inputs fixed when comparing the option.
