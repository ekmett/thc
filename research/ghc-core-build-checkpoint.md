# Complete-Core build investigation

This records the original optional-Hadrian-patch and interface-size checks,
preserved from [THC 040b1433](https://github.com/ekmett/thc/blob/040b14338accd292e6c71b8f80bf31cb50ea5e40/docs/ghc-core.md).
The statements below describe that investigation, not the current status of
all compiler installations. No new build or measurement is reported here.
Use the [current GHC library Core guide](../docs/ghc-core.md) for the release,
compiler selection and installation checks required by THC.

## Patch scope and checks

The Hadrian patch was dry-run against GHC's `ghc-9.14.1-release` source
(`902339d332fb4ce2b3c87dcac1ee6495d41ad886`) and upstream master at
`35bf6f4bcf06675565992725df62e837bc7788ce`. It selects Haskell
compilation of library packages after Stage0, for every built library way.
It includes GHC's compiler library as well as ordinary libraries; excluding
the compiler would make this an incomplete library-wide rule. The installed
release libraries are built with a later stage.

A small `-O2` probe with an `OPAQUE` exported entry and a private `NOINLINE`
worker acquired an `extra decls:` section containing both bodies. Its interface
grew from 1,313 to 1,516 bytes; its native object was byte-for-byte identical,
and both executables returned the same result. This does not measure the size
of a patched `ghc-internal` build. A complete compiler rebuild has not been
validated here.

The Hadrian flag only covers libraries built by that GHC source tree. Ordinary
project packages can emit Core when THC builds them; independently installed
packages require their own complete-Core build. `rts` C code and primop
semantics are separate from Haskell interface payloads. GHC API changes remain
explicit compatibility work; retaining library Core removes one source of
version-specific scaffolding, not those obligations.
