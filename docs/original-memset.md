# Original ByteString memset

The runtime accepts the original ByteString 0.12.2.0 unsafe ccall declaration
with Addr#, Int32#, Word64# and State# arguments and a State#/Addr# tuple
result. The complete installed unit ID remains in the exported descriptor;
the existing pinned ByteString unit policy also recognizes its installed suffix.
Other units, versions, conventions, safety modes and representations reject.

The operation checks the complete destination range and ownership before
writing, fills each byte with the low eight bits of the C int, and returns the
identical destination address. It uses existing managed or owned native storage,
including interior addresses. Whole-cell byte writes invalidate overlapping managed
pointer cells; partial pointer-cell overwrites reject before mutation. Literal storage, unowned numeric addresses, invalid ranges,
unsigned lengths beyond signed Long, freed owners and cross-context native
owners reject. No temporary String, byte array or foreign allocation is needed.

Run `cabal run exe:thc-fixtures -- original-memset` to compile the genuine
installed ByteString wrapper and its native oracle, export pre/post Core, and
strictly audit both entries. The oracle records the returned pointer offset and
every buffer byte for 198 cases. OriginalMemsetTest checks both backends before
compilation and on the first installed call, together with raw original CInt
inputs, descriptor rejection, storage bounds and lifetime controls.
