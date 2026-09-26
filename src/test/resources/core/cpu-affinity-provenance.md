# Public CPU-affinity declarations

`cpu-affinity-descriptors.json` retains the two foreign-call descriptor objects
from genuine GHC 9.14.1 post-Tidy export of `runtime/THC/Thread.hs`, without changing
their target, convention, safety, arguments, or result. The Kotlin tests use
explicitly synthetic callers; they do not claim those callers are GHC output.

The declarations moved from `THC` to `THC.Thread` with the wider runtime API.
Both retained descriptor objects were compared against the fresh module export
and are unchanged; `THC` re-exports the same public affinity functions.

Source SHA-256: `49e68ceb577e9d50f1c4b84f811d6a1d502a9ed8f3a7b9dcb97d499ed6b1367e`.
Fresh complete Core SHA-256:
`79011212348b7db3163702ca1956b044c5a8070422577996a9d8595b1084d744`.

Regenerate with the pinned compiler:

```sh
THC_CORE_OUT="$PWD/build/runtime-services-export-concrete/core" \
THC_GHC_OUT="$PWD/build/runtime-services-export-concrete/ghc" \
  compiler/export.sh -XHaskell2010 -iruntime \
    -fplugin-opt=THC.Plugin:post-tidy \
    examples/THC/RuntimeServices.hs runtime/THC/Internal/JIT.hs
```

The source is also compiled and linked natively by `cabal test cpu-affinity-api
-fdevelopment`; its compatibility C shim returns zero for both queries.
