# Original ByteString UTF-8 validation

`cbits/is-valid-utf8.c` is unchanged from ByteString 0.12.2.0 bundled with
GHC 9.14.1. Its SHA-256 is
`d25c2ce0260fe4509c59ad400cba5df33dfafb9935ef3099ba04b26a2ce36e65`.
The complete upstream BSD license remains embedded in the source and is also
included with the generated bitcode resource.

The Linux x86_64 build defines `__STDC_NO_ATOMICS__=1`, selecting the original
portable fallback rather than the CPUID/atomic SIMD dispatcher. The compiled
artifact is checked for a defined validator and absence of native `memcpy`
dependencies. Its `memcpy` loads must remain in managed LLVM execution.

The runtime admits only the installed safe and unsafe pointer declarations
from `Data.ByteString.Internal.Type`: `Ptr Word8 -> CSize -> IO CInt`.
The ByteArray# declarations and optional text SIMDUTF C++ implementation are
outside this interface.
