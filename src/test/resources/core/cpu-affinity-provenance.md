# Public CPU-affinity declarations

`cpu-affinity-descriptors.json` retains the two foreign-call descriptor objects
from genuine GHC 9.14.1 post-Tidy export of `runtime/THC/Thread.hs`, without changing
their target, convention, safety, arguments, or result. The Kotlin tests use
explicitly synthetic callers; they do not claim those callers are GHC output.

The declarations moved from `THC` to `THC.Thread` with the wider runtime API.
Both retained descriptor objects were compared against the fresh module export
and are unchanged; `THC` re-exports the same public affinity functions.

Source SHA-256: `1c929b48f12bf5c094d1d3963ab282fbeafd59d6dbda6498c6798fff29f36260`.
Original complete Core SHA-256:
`8f500f032e8d6f4db7ca914485ddb24c86e178ce83c620279ade20c6d2a27a11`.

Regenerate with the pinned compiler:

```sh
THC_CORE_OUT="$PWD/build/cpu-affinity-api/core" \
THC_GHC_OUT="$PWD/build/cpu-affinity-api/ghc" \
  compiler/export.sh -XHaskell2010 -iruntime \
    -fplugin-opt=THC.Plugin:post-tidy runtime/THC/Thread.hs
```

The source is also compiled and linked natively by `cabal test cpu-affinity-api
-fdevelopment`; its compatibility C shim returns zero for both queries.
