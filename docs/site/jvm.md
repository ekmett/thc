# Module THC

Mixed Kotlin and Java reference for THC, the experimental Haskell runtime on
Truffle/Graal. Host applications should start with `thc.executionContext` and
`thc.loadEntry`, and read the site's embedding guide for authority and lifetimes.

This is an implementation reference, not a stable SDK. Java public visibility
and Kotlin public declarations do not promise stable runtime carriers, node
layouts, frame slots, calling conventions, or generated Truffle APIs. Generated
DSL and SIMD sources and test fixtures are intentionally excluded.

# Package thc

Experimental launcher/embedding entrypoints, Core loading and package validation,
plus executable diagnostics. Start with `executionContext` and `loadEntry`.
`CoreModules` and manifest utilities describe the current serialized boundary;
they do not bypass strict runtime admission.

# Package thc.runtime

Runtime implementation details for laziness, calls, storage, native providers,
and Truffle execution. These public declarations are not a supported embedding
ABI. Their ownership and representation contracts may change with the runtime.
