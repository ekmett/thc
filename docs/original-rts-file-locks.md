# Original RTS file bookkeeping

The exact GHC 9.14.1 `ghc-internal` declarations `lockFile` and `unlockFile`
are context-local RTS bookkeeping calls, not operating-system advisory locks.
They do not inspect, acquire, close or validate a host or managed descriptor.
`Word64` keys, device and inode values retain all 64 bits. Any nonzero canonical
signed `CInt` writer flag requests exclusive access. Readers share one identity;
a writer conflicts with every existing claim. Results are 0/success,
-1/conflicting lock, and 1/unknown unlock. Neither call changes errno.

Repeated reader claims for the same key and identity require corresponding
unlocks. Reusing a live key for a different identity is explicitly unsupported:
it raises `RuntimeFault` before mutation, rather than reproducing the RTS hash
table's insertion-order-dependent pathological duplicate-key behavior. Reader
counts exceeding the original signed-int range also fail before mutation.
Disposal clears this separate context-owned table.

`ManagedFiles` admission/retiring-owner claims are independent and unchanged.
Raw close and dup2 never release RTS keys; dup never copies them. The original
GHC FD source releases its RTS key before closing its descriptor.
