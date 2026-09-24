# Bounded FloatX4 ByteArray access

This slice adds exactly six local GHC9.14.1 operations, without a vector ABI:

| Operations | Index stride | Access width |
| --- | ---: | ---: |
| indexFloatX4Array#, readFloatX4Array#, writeFloatX4Array# | 16 bytes | 16 bytes |
| indexFloatArrayAsFloatX4#, readFloatArrayAsFloatX4#, writeFloatArrayAsFloatX4# | 4 bytes | 16 bytes |

Index accepts an immutable ByteArray# and Int#, returning the exact
`VecRep 4 FloatElemRep`. Read accepts a mutable byte array, Int# and State#,
returning `(# State#, FloatX4# #)` only to an immediate validated case. Write
accepts the mutable array, Int#, exact local vector and State#, returning State#.
There are two logical read fields, not State plus four unpacked scalar lanes.
Whole-tuple escape, vector function/join/capture/constructor transport and
foreign/Addr memory remain unsupported.

The closed family mapping keeps signed Int32, unsigned Word32 and Float32
identities distinct. Raw aggregate annotations are accepted only at the read
producer and whole-binder structural sites; exact integer lane/constructor
counts, unlifted/non-coercion binders, State order and lexical identities remain
required. Generic aggregate rules are unchanged.

Memory is native-endian ordinary non-atomic byte-array storage. Full Long
bounds are checked before scaling or narrowing:
`size >= 16 && index >= 0 && index <= (size - 16) / stride`.
Invalid stores leave all bytes unchanged; invalid ranges deoptimize before
exception allocation. State is checked before access or result publication.

The intended storage path preserves the existing immutable FloatVector carrier,
using ByteVector128 transfers and reinterpretation, with per-int byte reversal
on big-endian hosts. It performs no numeric conversion, lane extraction/repack,
NaN canonicalization or floating arithmetic. Big-endian support and packed-code
survival require separate verification; Vector API use alone proves neither.

Raw-bit witnesses use existing Word32/Float scalar-array aliases, not additional
scalar bitcast primops. Exact portable assertions cover finite values, signed
zeros, subnormals, infinities and selected quiet-NaN payloads. Selected signaling
NaN movement is recorded separately on tested hosts: Java permits quieting when
copying floating scalars, so no cross-platform scalar sNaN bit guarantee or
arithmetic NaN-payload guarantee is made. Native invalid offsets are not an oracle.

Finite graph witnesses consume all four lanes separately from raw-bit semantic
tests. Graph acceptance must prove live packed caller-array memory operations
and reject private vector/carrier/payload allocations and unexpected calls;
public result boxes and exact deoptimization metadata remain distinguished.

Implementation and evidence are in progress. No full-suite or packed-code
verification claim is made at this checkpoint.
