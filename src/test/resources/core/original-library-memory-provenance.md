The two JSON declarations are original GHC 9.14.1 exports, not generated
approximations. Only JSON whitespace/key order has changed. Surrounding Kotlin
callers are explicitly synthetic; `test/fixtures/run-library-memory` separately
exercises the original Haskell library implementations and native output.

* `original-array-memcpy-descriptor.json`: array 0.5.8.0's `memcpy_freeze` /
  `memcpy_thaw` declarations in `Data/Array/Base.hs`. The declaration was retained
  in Alex 3.5.4.2's exported `Output` module, SHA-256
  `5ca87bc502b96c468c4b45647776d77693510b4bb2febd1af4fbaeb765091664`.
  Both operands have the unlifted byte-array carrier. The result is an address
  alias of the destination, not a newly allocated buffer. The operations copy
  distinct allocations; they do not relax memcpy's non-overlap precondition.
* `original-bytestring-strlen-descriptor.json`: bytestring 0.12.2.0's `c_strlen`
  in `Data/ByteString/Internal/Type.hs`, returning `CSize` (Word64# on this pinned
  target). The declaration was retained in unix 2.8.8.0's exported
  `System.Posix.PosixPath.FilePath`, SHA-256
  `e341553bb7289341df45e43917d9dc17a3f7e67074c9afbd315326480175a26b`.
  This differs from ghc-internal's Int# result but uses the same bounded address
  scan through the first NUL byte.

The runtime variants preserve owned/managed memory restrictions; they do not
provide an arbitrary native-address bridge. Admitting these declarations is not
proof that the entire Alex, bytestring, or unix package closure executes.
