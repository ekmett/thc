# Arithmetic exception primops

THC lowers GHC 9.14.1's `raiseDivZero#`, `raiseOverflow#`, and
`raiseUnderflow#` with their original lazy `SomeException` payloads:
`ghc-internal:GHC.Internal.Exception.Type.divZeroException`,
`overflowException`, and `underflowException`. The empty unboxed tuple
argument is a real logical argument with no physical slots. AST and bytecode
execution use the same guest exception and catch machinery as `raise#`;
forcing the payload is left to the Haskell handler. The plugin's interface
closure, the strict Core auditor, and runtime reachability all retain the
implicit original global. Missing original definitions fail linking. No
exception value or Typeable dictionary is fabricated by THC.

The operation is representation-polymorphic and does not return. THC currently
admits scalar and concrete tuple result proofs; sum and vector results remain
outside this slice, so the primop checklist marks it partial. A fixture-free
ordinary test verifies that all three payloads stay unforced and preserve
identity across failure memoization on both backends.

The original-GHC integration fixture has six opaque scalar/tuple roots. Its
handler uses the original `fromException` to match `ArithException`. The Haskell
producer obtains complete installed Core from the selected GHC, exports pre-
and post-Tidy consumers, checks all twelve strict audits, and records a
42-row native oracle and input/output hashes. The named full-Core JVM suite
checks those receipts, then compares native, interpreted, and first-installed
compiled AST/bytecode results, including a cold first throw and a separately
profiled throw. It does not run as part of the stock-GHC default suite.

Prepare and run the explicit full-Core check with a GHC 9.14.1 installation
whose `ghc-internal` interfaces contain complete Core:

```sh
cabal run exe:thc-fixtures --offline -- arithmetic-exceptions
./gradlew --no-daemon arithmeticExceptionsFullCoreTest
```

The producer fails before writing a success manifest if any strict audit
rejects a root. In the latest combined Linux check, the 42 native rows passed
and each of the twelve arithmetic audits had zero missing globals and one
remaining unrelated `Conc.Bound` foreign-registration issue. The standalone
fixture-independent lazy-payload test passed in both default and dense modes.
The full-Core JVM suite is still pending a successful strict fixture and must
not be reported as passed yet. Regular CI runs `ArithmeticExceptionLazinessTest`
without requiring installed complete Core.

Semantic basis: pinned GHC commit
`902339d332fb4ce2b3c87dcac1ee6495d41ad886`,
[`primops.txt.pp`](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Builtin/primops.txt.pp#L2706),
[`Exception.cmm`](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Exception.cmm#L653),
and the repository's unchanged pinned `GHC/Internal/Exception/Type.hs`.
