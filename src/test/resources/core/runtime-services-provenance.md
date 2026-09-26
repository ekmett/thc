# Public runtime service declarations

`runtime-services-descriptors.json` retains the three genuine foreign-call
descriptor objects from GHC 9.14.1's post-Tidy export of
`runtime/THC/Internal/RuntimeABI.hs`. No convention, safety, target, arity,
argument or result representation has been changed. Callers assembled by Kotlin
tests remain explicitly synthetic; the descriptor objects themselves are GHC
output, not handwritten ABI approximations.

Source SHA-256:
`ab41c49885ca88d192f8caf27589036c1abe9a2f3afc560ff5753eba968d517c`.
Complete exported module SHA-256:
`de70bd5d10c1169ba6f95ce0d6ed140bf386613060d1cc9281465776576f52da`.
Retained descriptor file SHA-256:
`362de7b4f133dcaccc9d837690a89f248ca7e5979fd0e37e277448e41665d9bb`.

Regenerate the complete export using the pinned compiler:

```sh
THC_CORE_OUT="$PWD/build/runtime-services-export/core" \
THC_GHC_OUT="$PWD/build/runtime-services-export/ghc" \
  compiler/export.sh -XHaskell2010 -iruntime \
    -fplugin-opt=THC.Plugin:post-tidy \
    examples/THC/RuntimeServices.hs runtime/THC/Internal/JIT.hs
```

The retained object is indexed by `target.symbol` for each of
`thc_runtime_v1_query`, `thc_runtime_v1_control` and `thc_runtime_v1_trace`, copied
from that export's nested foreign descriptors. All successful scalar results use
`Int64Rep`; selector arguments use `Int32Rep`; trace payload pointers use
`AddrRep`; the explicit erased state token and state/result tuple are retained.

The same command exports genuine wrappers and the four public API smoke roots
in `THC.RuntimeServices`. That export by itself is **not** evidence of executing
the complete wrappers in THC. Native `cabal test runtime-services-api
cpu-affinity-api -fdevelopment` and the Safe Haskell compile checks separately
exercise the public compatibility implementation.
