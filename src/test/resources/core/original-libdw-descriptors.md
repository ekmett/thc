These four certificates were copied verbatim (JSON whitespace aside) from the
THC export of GHC 9.14.1's original `GHC.Internal.ExecutionStack.Internal`.
Original module SHA256:
`5757ba49baba32d14d028cb580b83560cd85faf8ac0c5edb15225f7216a2d1fd`.
No surrounding expressions or source data are included. Test consumers, variable
names, and occurrence proofs are explicitly synthetic; declarations are original.

The selected full-Core Linux GHC and local macOS GHC both have `USE_LIBDW=0` in
`rts/include/ghcautoconf.h`. The runtime implements that unavailable backend:

* [GHC 9.14.1 LibdwPool.c](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/rts/LibdwPool.c)
  returns NULL from `libdwPoolTake`; `libdwPoolClear` is a no-op.
* [GHC 9.14.1 Libdw.c](https://github.com/ghc/ghc/blob/ghc-9.14.1-release/rts/Libdw.c)
  returns NULL from `libdwGetBacktrace`; `libdwLookupLocation` returns failure 1
  without reading the session or writing the location.

`LibdwUnavailableNative.hs` checks this selected RTS configuration, pointer and
buffer behavior, and the original `collectStackTrace` result. Kotlin checks both
backends and the first installed compiled entries using these declarations.
This does not certify the complete execution-stack closure: original function
address literals and weak finalizers remain separate unsupported dependencies.
The managed IPE stack-info backend is independent of native DWARF availability.
