# Public runtime service declarations

`runtime-services-descriptors.json` retains the three genuine foreign-call
descriptor objects from GHC 9.14.1's post-Tidy export of
`runtime/THC/Internal/RuntimeABI.hs`. No convention, safety, target, arity,
argument or result representation has been changed. Callers assembled by Kotlin
tests remain explicitly synthetic; the descriptor objects themselves are GHC
output, not handwritten ABI approximations.

Source SHA-256:
`002524a993eaddd14e2935d56868f00c99f6d57c23fbc3a06ab1608c21827dd8`.
Complete exported module SHA-256:
`ace1c22243c8cefebb8ee9e80cada0bb46b6c5b8bdab44a3c8497ca87c7b9f38`.
Retained descriptor file SHA-256:
`362de7b4f133dcaccc9d837690a89f248ca7e5979fd0e37e277448e41665d9bb`.

Regenerate the complete export using the pinned compiler:

```sh
THC_CORE_OUT="$PWD/build/runtime-services-export-concrete/core" \
THC_GHC_OUT="$PWD/build/runtime-services-export-concrete/ghc" \
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

After making the private counter helpers concrete (`Int64`, `Int`, `Word64`),
the complete modules were freshly exported and all three retained descriptor
objects compared structurally against every occurrence in the new export.
Their ABI objects are unchanged. The concrete helpers avoid retaining an
unneeded `Integral Word64` dictionary and its `Real`/`toRational`/GMP closure.

Separately, the real multi-package smoke in
`test/fixtures/run-runtime-services` passed on 2026-09-26 through `thc run`,
using the actual runtime library, complete installed Core and original `:Main`
startup/Handle shutdown. Its strict audit accepted 73,671 supplied bindings,
2,172 reachable bindings, zero missing globals and zero issues. Default and
dense bytecode runs both produced the required THC-specific success marker,
including real Unicode/NUL trace events and nested span output. The AST attempt
was rejected by its existing original process-signal startup limitation before
the API actions; it is not a full-wrapper AST success claim.
