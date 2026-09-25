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
GHC FD source releases its RTS key before closing its descriptor. This checkpoint
does not execute the whole original `FD.release`, `mkFD`, open or Handle lifecycle.
It adds no acquisition, weak/finalizer, libdw or readiness support.

`thc-fixtures original-rts-locks --require-supported` hydrates the installed
original FD interface, selects its actual private FCallIds, checks `eqType`, and
specializes ordinary higher-order consumers with those original Ids. It retains
the declaration projection, unspecialized template and pre/post-Tidy consumers;
no foreign declaration is invented and no JSON proof is rewritten. Native
observations come from GHC's bytecode-compiled specialized Core invoking the
actual RTS symbols, not a separately linked oracle executable or whole FD module.
The installed interface path is recorded but installed artifacts are not hashed.

The 14 native observations cover duplicate reader keys, distinct reader keys,
writer conflicts, negative nonzero flags, full-width identities, missing unlocks
and errno immediately before/after each call. Kotlin independently checks their
meaning, malformed descriptors and stored operands, State-before-effect, context
isolation, descriptor independence and disposal. Both interpreters and every
first-installed compiled target retain exact entry counts and validity.
