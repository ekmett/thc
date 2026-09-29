# Native address projection

The pinned GHC coercions are `addr2Int# :: Addr# -> Int#` and
`int2Addr# :: Int# -> Addr#`. THC preserves all 64 bits, including zero and the
high bit. An arbitrary integer yields an opaque address carrier: it does not
provide permission to read process memory or call C with that pointer.

Explicitly pinned arrays own aligned native storage from allocation. Projection
returns that segment's real address, without copying, moving, or pinning again.
Buffer-only interop and native pointer views share this same storage. Ordinary
mutable and immutable heap allocations remain moving JVM arrays and reject
numeric projection; unsafe freeze does not promote storage.

The opt-in [native byte-array creation policy](bytearrays.md) also gives ordinary
guest arrays stable physical backing. They project and recover through the same
owner/range registry while remaining strong-unpinned and compactable. No array is
promoted at a foreign call; host-supplied raw arrays remain unprojectable.

Static literals and explicitly constructed runtime info-table images can acquire
their read-only native materialization. Static info tables retain their allocation
identity for weak stack-provenance lookup; ordinary immutable guest arrays remain
unprojectable. This classification neither pins nor copies a guest array.
The current
context owns a weak backing-to-image index; every
image owns a shared FFM arena and its address is `MemorySegment.address()`,
never an identity hash or an encoded handle. Aliases share one image and offsets.
A live registered integer address reconstructs the original managed alias,
including its existing pointer-cell and stack-provenance identity. The native
copy and original bytes cannot diverge through supported operations because
both are read-only. Immutable constructors copy their input, raw-array exports
return defensive copies, and pointer-cell installation requires mutable storage.
Internal buffer transport borrows a read-only view, rather than using the
defensive-copy host export API.
Each native allocation reserves one extra physical byte so an image's valid
one-past address cannot coincide with another image's base. This extra byte does
not enlarge the logical image or relax its managed access bounds.

Integer values do not keep the allocation alive. Managed aliases keep the weak
backing key alive; native transport views keep both backing and image alive.
Synchronous C calls use reachability fences until return. Dead images are cleaned
without re-entering LLVM, and context disposal closes surviving literal-image
arenas. Pinned-array automatic arenas instead follow the lifetime of their
aliases and buffer views, including a view retained after a context closes.
Previously issued immutable `CbitsBuffer` views have an explicit `toNative`
transition to this same literal image. Pinned mutable views expose their existing
native segment; moving-heap views have no such transition. Sulong
cannot pin arbitrary JVM arrays on demand: its native-pointer conversion calls
`toNative` and then requires `asPointer` to succeed.
Resolution uses only the current context's live registry: a numeric address
from another context grants no access to that context's storage.

StablePtr handles acquire an opaque native identity lazily, when passed to C or
projected to bits. The context's stable-pointer table keeps the lazy referent
rooted until `freeStablePtr` or disposal; only an exact live token in that context
recovers its handle. C can retain and return the token across calls, including
through native pointer cells. The identity has no guest byte storage, and neither
its numeric bits nor a C copy extends the lifetime after explicit free. An
unrecognized pointer result remains opaque and unowned. This is not a native
GHC closure address or callback/re-entry implementation.

Moving-heap arrays reject numeric projection. Raw byte views cannot expose
managed pointer cells. The [typed package-call boundary](c-finalizers.md) can
encode and reconcile supported pointer-bearing pinned allocations while retaining
the complete ownership graph; this is not arbitrary-pointer memory access.

Primary contracts:

- [Truffle InteropLibrary pointer messages](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/interop/InteropLibrary.html#toNative(java.lang.Object))
- [Sulong native conversion](https://github.com/oracle/graal/blob/master/sulong/projects/com.oracle.truffle.llvm.runtime/src/com/oracle/truffle/llvm/runtime/library/internal/LLVMNativeLibraryDefaults.java)

The installed `llvm-language-25.3.4.1.jar` also contains the explicit virtual
byte-array conversion rejection in `LLVMNativePointerSupport.ToNativePointerHelper`.
