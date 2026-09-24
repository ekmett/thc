# SmallArray primitives

THC supports the eight GHC 9.14.1 fundamentals
`newSmallArray#`, `readSmallArray#`, `writeSmallArray#`,
`indexSmallArray#`, `unsafeFreezeSmallArray#`, `sizeofSmallArray#`,
`sizeofSmallMutableArray#`, and `getSizeofSmallMutableArray#`, plus
`cloneSmallArray#`, `cloneSmallMutableArray#`, `copySmallArray#`,
`copySmallMutableArray#`, and `unsafeThawSmallArray#` in both execution
backends. The last mutable size form is deprecated by GHC but still occurs in
exported Core. Shrinking and atomic SmallArray operations remain outside this slice.

SmallArray and ordinary Array references share GHC's unlifted boxed
representation, but use distinct managed storage carriers. A primitive for
one family rejects the other's carrier. Cells store lifted references
without entering them; reads and indexing return the same references.
Unsafe freeze and thaw preserve storage identity. Clones allocate shallow,
independent storage. Mutable self-copy permits overlapping ranges; immutable
source copy requires distinct storage. Both ranges and the state operand are
checked before mutation. Invalid raw sizes or indices fail closed in THC; GHC
does not define those raw primitive cases.

`thc-fixtures small-arrays` builds a GHC native oracle and exports the
`smallComposite` Core entry before and after Tidy. The composite exercises
all thirteen primops, including the three size forms, overlapping mutable
copy, independent clones, a read snapshot followed by a write, and a recursive
bottom initializer that remains unevaluated.
Strict audits require exact argument flags, Int/State/element representations,
and the distinct logical tuple results. `SmallArrayTest` checks each native
row against an independent scalar model and executes it through AST and
bytecode before and after explicit guest compilation. It also checks
reference identity, family separation, and bounds behavior.
