# Native address projection

The pinned GHC coercions are `addr2Int# :: Addr# -> Int#` and
`int2Addr# :: Int# -> Addr#`. THC preserves all 64 bits, including zero and the
high bit. An arbitrary integer yields an opaque address carrier: it does not
provide permission to read process memory or call C with that pointer.

Immutable literals and pointer-free immutable allocation images can acquire a
real native copy. The current context owns a weak backing-to-image index; every
image owns a shared FFM arena and its address is `MemorySegment.address()`,
never an identity hash or an encoded handle. Aliases share one image and offsets.
A live registered integer address reconstructs the original managed alias,
including its existing pointer-cell and stack-provenance identity. The native
copy and original bytes cannot diverge through supported operations because
both are read-only. Immutable constructors copy their input, raw-array exports
return defensive copies, and pointer-cell installation requires mutable storage.
Mutable array aliases keep their existing behavior and remain ineligible for
projection, including after `unsafeFreezeByteArray#`.
Each native allocation reserves one extra physical byte so an image's valid
one-past address cannot coincide with another image's base. This extra byte does
not enlarge the logical image or relax its managed access bounds.

Integer values do not keep the allocation alive. Managed aliases keep the weak
backing key alive; native transport views keep both backing and image alive.
Synchronous C calls use reachability fences until return. Dead images are cleaned
without re-entering LLVM, and context disposal closes every surviving arena.
Previously issued immutable `CbitsBuffer` views have an explicit `toNative`
transition to this same image. Mutable views have no such transition. Sulong
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

Mutable managed arrays still reject numeric projection. Supporting them requires native-primary storage or a
complete coherent storage abstraction, including escaped raw array aliases,
concurrent access, and real pointer-cell encoding. A temporary native copy or
native identity token would not satisfy that contract. This increment does not
add generic foreign-pointer ownership, arbitrary-pointer memory access, or
native function pointers.

The native Haskell oracle covers null, all-bit integer roundtrips, pointer offsets,
one-past pointers, and alias writes across GC. Kotlin tests exercise actual native
bytes, old and new Sulong transports, original MD5 input, owner closure, denied
memory access, and the first installed compiled AST/bytecode entries.

Primary contracts:

- [Truffle InteropLibrary pointer messages](https://www.graalvm.org/truffle/javadoc/com/oracle/truffle/api/interop/InteropLibrary.html#toNative(java.lang.Object))
- [Sulong native conversion](https://github.com/oracle/graal/blob/master/sulong/projects/com.oracle.truffle.llvm.runtime/src/com/oracle/truffle/llvm/runtime/library/internal/LLVMNativeLibraryDefaults.java)

The installed `llvm-language-25.3.4.1.jar` also contains the explicit virtual
byte-array conversion rejection in `LLVMNativePointerSupport.ToNativePointerHelper`.
