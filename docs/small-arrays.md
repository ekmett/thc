# SmallArray primitives

THC supports the eight GHC 9.14.1 fundamentals
`newSmallArray#`, `readSmallArray#`, `writeSmallArray#`,
`indexSmallArray#`, `unsafeFreezeSmallArray#`, `sizeofSmallArray#`,
`sizeofSmallMutableArray#`, and `getSizeofSmallMutableArray#`, plus
`cloneSmallArray#`, `cloneSmallMutableArray#`, `copySmallArray#`,
`copySmallMutableArray#`, `unsafeThawSmallArray#`, `freezeSmallArray#`, and
`thawSmallArray#` in both execution
backends. The last mutable size form is deprecated by GHC but still occurs in
exported Core. [Boxed CAS](boxed-cas.md) adds `casSmallArray#`; [shrinking](scalar-memory-utilities.md) preserves the logical prefix.

SmallArray and ordinary Array references share GHC's unlifted boxed
representation, but use distinct managed storage carriers. A primitive for
one family rejects the other's carrier. Cells store either known boxed levity
without entering them; reads and indexing return the same references.
Unsafe freeze and thaw preserve storage identity. Safe freeze and thaw copy
the requested slice into independent shallow storage, including empty slices;
they preserve lazy element references. Clones also allocate shallow,
independent storage. Mutable self-copy permits overlapping ranges; immutable
source copy requires distinct storage. Both ranges and the state operand are
checked before mutation. Invalid raw sizes or indices fail closed in THC; GHC
does not define those raw primitive cases.
