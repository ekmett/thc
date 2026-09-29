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
unsupported. Payloads stay unforced across failure memoization.
