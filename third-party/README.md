# Third-party sources

The Git submodules in `pinned/` contain unmodified upstream releases. Git pins
each dependency to a commit; initialize them with:

```sh
git submodule update --init --depth 1
```

Do not add `--recursive`: THC does not need GHC's nested submodules. To reduce
the GHC working-tree footprint after checkout:

```sh
git -C third-party/pinned/ghc-9.14.1 sparse-checkout set libraries/ghc-internal rts
```

| Submodule | Release | Used sources |
| --- | --- | --- |
| `ghc-9.14.1` | GHC 9.14.1 | ghc-internal Haskell, headers and C routines; selected RTS routines |
| `bytestring-0.12.2.0` | ByteString 0.12.2.0 | UTF-8 validation |
| `text-2.1.3` | text 2.1.3 | UTF-8 measurement, reversal and utility routines |
| `zlib-1.2.11` | zlib 1.2.11 | Adler-32 and CRC-32 |

Upstream licenses stay in their repositories. Other redistribution notices are
in `licenses/`. THC patches stay in `patches/`, outside the submodules.
The standalone `pinned/openbsd-memchr-1.8.c` retains the OpenBSD source's full
license and supplies text's managed-memory-compatible memchr dependency.

The ByteString and text C builds select their portable non-atomic dispatch
configuration. The build checks the selected source identities and LLVM
dependencies; it does not substitute native libc accesses for managed buffers.
