# Original zlib checksum sources

`1.2.11/` contains unchanged files from the official
[zlib 1.2.11 archive](https://zlib.net/fossils/zlib-1.2.11.tar.gz).
The original zlib license and attribution are in `1.2.11/zlib.h` and
`1.2.11/README`; these files retain their upstream terms.
Archive SHA-256: `c3e5e9fdd5004dcb542feda5ee4f0ff0744628baf8ed2dd5d66f8ca1197cb1a1`.

The driver verifies every source/header hash before compiling `adler32.c` and
`crc32.c` with the package's configured zlib header. Its version must match
1.2.11. This bounded provider supplies checksum implementations as LLVM using
the existing managed pointer transport; it does not load native zlib, copy
guest buffers, or pin heap storage. Other zlib operations and source versions
remain unsupported.
