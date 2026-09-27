# Original ByteString byte sorting

Both backends implement the original bytestring-0.12.2.0 `fps_sort`
declaration: an unsafe `ccall` taking `Addr#`, `Word64#` and `State#`, returning
the singleton State tuple. The installed unit identifier is retained and
validated together with the exact argument, result and foreign-variable proofs.

The operation sorts unsigned bytes in the caller's existing writable range.
It has the same observable result as the original C unsigned-byte `qsort`;
the managed implementation uses 256 frequency counters. It validates the whole
range before mutation and borrows owned native storage for the complete call.
Aliases retain the same allocation, and bytes outside a slice are unchanged.
Immutable storage, pointer-cell overlap, expired or foreign native allocations,
unowned numeric addresses, and unrepresentable or out-of-bounds counts reject.
A zero count accepts null without dereferencing it.

`cabal run exe:thc-fixtures -- bytestring-sort` obtains the genuine FCallId from
the installed `Data.ByteString.Internal.Type` interface and checks its GHC type
against typed Haskell consumers. The producer exports pre/post Core and executes
the same declaration with native GHC. Its 17 observations cover empty and
singleton ranges, duplicate and high-bit bytes, all 256 byte values, and interior
slices with surrounding sentinels.

`ByteStringSortTest` compares those results on managed arrays, mutable and pinned
allocations, plus owned native allocations on Linux x86_64. It checks the first
call after installation and retained compilation on both backends, along with
rejection before mutation and exact descriptor controls. The default and dense
handoff tasks run the same checks in separate JVMs. This contract covers this
foreign leaf; other ByteString foreign declarations have their own admission.
