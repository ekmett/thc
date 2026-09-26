# Public CPU-affinity declarations

`cpu-affinity-descriptors.json` retains the two foreign-call descriptor objects
from genuine GHC 9.14.1 post-Tidy export of `runtime/THC.hs`, without changing
their target, convention, safety, arguments, or result. The Kotlin tests use
explicitly synthetic callers; they do not claim those callers are GHC output.

Source SHA-256: `5fb0936835a94e0494daf1772372aa1009cd4192bc597644809c50cd92b5cab5`.
Original complete Core SHA-256:
`cae933b0a11709d78db9bd6687b8295b504ce83571ea1d966730916ab711e056`.

Regenerate with the pinned compiler:

```sh
THC_CORE_OUT="$PWD/build/cpu-affinity-api/core" \
THC_GHC_OUT="$PWD/build/cpu-affinity-api/ghc" \
  compiler/export.sh -fplugin-opt=THC.Plugin:post-tidy runtime/THC.hs
```

The source is also compiled and linked natively by `cabal test cpu-affinity-api
-fdevelopment`; its compatibility C shim returns zero for both queries.
