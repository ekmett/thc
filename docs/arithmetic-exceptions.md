# Arithmetic exception primops

GHC 9.14.1 declares `raiseDivZero#`, `raiseOverflow#`, and `raiseUnderflow#`
as `(# #) -> b` with a representation-polymorphic, non-returning result.
The empty tuple remains a strict logical argument even though it has no
physical slots. `rts/Exception.cmm` forwards respectively
`GHC.Internal.Exception.Type.divZeroException`, `overflowException`, and
`underflowException` directly to `stg_raisezh`.

THC retains these exact `ghc-internal` global dependencies in its Core linker,
strict auditor, and plugin interface-closure traversal. The plugin obtains the
original Ids through GHC's current session; no exception constructor,
`SomeException` dictionary, Typeable witness, or wrapper is synthesized.
Missing original bodies remain ordinary link/audit failures. AST and bytecode
lowering sequence the empty argument and pass the lazy global payload through
the existing guest-exception machinery, including failed-thunk memoization.
Scalar and concrete tuple results are admitted; sum and vector results remain
outside this slice. The generated checklist therefore labels these partial.

The fixture has six opaque roots: each primitive has both an `Int#` and an
unboxed tuple result. Its catch handler calls the original `fromException` and
matches `ArithException`. The producer uses the production `Installed`/`Project`
APIs to acquire complete original definitions from the selected GHC interfaces.
The copied immutable ZIPs retain their target-layout and build-input receipts;
the production package reader validates them before composition with each genuine
pre/post consumer. A thin compiler reports the missing `complete-interface-core`
prerequisite. Separate interface-closure exports check implicit discovery.
Kotlin checks compare native results with an independent arithmetic model,
withhold the throwing input until the actual guest call graph is compiled,
also require a separately profiled exception path to remain installed after its
first compiled throw without recompilation, and test lazy payload identity plus
failure memoization in both backends.

## Source checkpoint validation

The native fixture compiled with Core/STG lint on the local GHC 9.14.1 and
produced all 42 expected rows. The plugin and fixture producer passed GHC
`-fno-code` checks. Auditor, primop checklist and fixture/CI selection checks
passed. JVM execution and the complete exported original dependency closure
still require the serialized Linux worker; this checkpoint does not record a
successful runtime result.

The recovery onto integration candidate `3b2f28ac` preserves this payload and
lowering design. The merged plugin passes GHC `-fno-code`; the auditor passes
121 tests in both normal and optimized Python modes, and the 13 primop coverage
checks pass. These are source checks, not a new JVM result.

The historical Linux `bb042886` gate produced 42 native rows, then stopped at
the first strict audit with 33 missing globals and 33 issues. That fixture used
the limited 54-module pinned-source export. A read-only comparison found
all 30 ordinary missing definitions in the existing 268-module installed
`ghc-internal` archive (SHA-256
`f4f17ddc9eb1e5a823668039a22c42aa7c846c5b57b854d372bddf12661fcd60`),
which carries the original target layout. The other three names are synthetic
libdw FCallIds, requiring their foreign descriptors rather than Haskell bodies.

The new producer replaces that source export with complete installed Core; it
never overlays both definitions of an original module. Its current helper
refreshes structured function labels while reusing the selected compiler and
production package cache. `--core-only` refreshes all exports/audits without
building or running the native oracle or writing a complete fixture manifest.
Full preparation reuses the native oracle only when a separate receipt matches
its exact source inputs, compilation arguments, selected compiler/registration
and all output hashes. The provider passes GHC 9.14.1 `-fno-code -Wall -Werror`;
the package composition checks pass all 13 tests in normal and optimized Python,
and the fixture cache/selection checks pass 56, 53 and 58 tests respectively.
Finalizer callbacks and unsupported stack getter paths remain separate runtime
work; neither old audit counts nor archive membership proves that the current
complete closure passes.

Run on the pinned worker, with its existing GraalVM 25.3.4.1 and GHC 9.14.1:

```sh
cabal run exe:thc-fixtures --offline -- arithmetic-exceptions --core-only
cabal run exe:thc-fixtures --offline -- arithmetic-exceptions
./gradlew --no-daemon test --tests thc.runtime.ArithmeticExceptionsNativeTest --tests thc.GuestExceptionsTest
python3 scripts/test-audit-core.py
python3 scripts/test-core-package-manifest.py
python3 -O scripts/test-audit-core.py
python3 scripts/test-primop-coverage.py
python3 scripts/primop-coverage.py --check
python3 .github/scripts/test_fast_inputs.py
python3 .github/scripts/test_fast_fixtures.py
python3 .github/scripts/test_fast_select.py
```

The fixture producer requires all twelve pre/post strict audits to succeed
before writing its manifest. Any remaining original-library dependency or
lowering failure must stay visible and be fixed before runtime support is
claimed.

Semantic basis: pinned GHC commit
`902339d332fb4ce2b3c87dcac1ee6495d41ad886`,
[`primops.txt.pp`](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Builtin/primops.txt.pp#L2706),
[`Exception.cmm`](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Exception.cmm#L653),
and the repository's unchanged pinned `GHC/Internal/Exception/Type.hs`.
