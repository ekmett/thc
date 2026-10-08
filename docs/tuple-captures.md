# Owned unboxed tuple captures

Ordinary closures and thunks on both backends can retain exact unboxed tuples.
The logical tuple remains an alias for flattened capture fields; it is not boxed
into a guest constructor, payload array or saved invocation frame. Nested tuple
boundaries remain part of the layout even though their physical fields flatten.

Long, Float and Double leaves use existing primitive capture properties. Known
references remain references and lifted leaves remain lazy. Exact evaluated
AddrRep leaves retain their checked managed address. Vector leaves use existing
fixed-species owned vector properties, independently of surrounding scalar
fields. State#/Proxy# and empty tuple components occupy no payload fields. A
closure capturing only an empty tuple needs no environment allocation.

Each closure or thunk owns its captures after the creator returns. Captures are
independent of reusable argument/result loans and durable PAP prefix storage.
Restoring a capture rebinds the original logical tuple to callee-local fields.
Closure inspection sees primitive/vector bytes and the actual lazy references,
not an additional tuple pointer. Existing exact shape, scalar carrier, vector
species and ownership checks still apply.

Genuine GHC pre/post export of escaped whole-tuple closure and thunk captures
remains unqualified; fixture-free owners cover runtime transport and sharing.

Tuples containing supported sums retain their exact logical tree and flattened
tag/payload fields, including lazy references and inactive null padding. The
[four-way native fixture](aggregate-heap-fields.md)
for nested producer/consumer captures and heap storage remains quarantined.

Recursive or lifted aggregate lets, global aggregate storage and unresolved
layouts remain unsupported. The [Core host ABI](site/embedding.md#load-a-core-entry)
transports supported aggregates as logical arrays. Sum captures are described
[separately](sum-inputs.md); local tuple joins keep using
their [same-frame capture path](tuple-joins.md).
