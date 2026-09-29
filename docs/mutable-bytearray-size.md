# Mutable byte-array size

Pinned GHC 9.14.1 defines two distinct primitives. `getSizeofMutableByteArray#`
accepts one unlifted managed byte-array reference and scalar `State#`, returning the logical
unboxed pair `(# State#, Int# #)`. Both backends evaluate/check State before reading
length and publish the size through one primitive Long destination. State consumes
no tuple storage. The result does not use a boxed pair or generic aggregate carrier.

`sizeofMutableByteArray#` is the separate deprecated pure primitive with a single
`Int#` result. It uses the existing typed array-length path. Its GHC warning matters:
it is unsafe around shrinking/resizing of the same reference. Native controls query
only live, stable references. After resize, all accesses use the returned array;
there is no promise about retired aliases. `shrinkMutableByteArray#` changes
an owned allocation's logical size in place; both queries observe that size,
not retained backing capacity. Pointer-cell truncation and host-array limits
are described in the [resize/shrink guide](resize-bytearrays.md).
Host-supplied raw `byte[]` values report their physical array length; guest-owned
heap and pinned allocations report their logical size, independent of retained
capacity. The unsafe pure query is not a synchronization mechanism.
