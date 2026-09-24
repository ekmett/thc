# Pinned memory / keepAlive peer review

Read-only review of `/home/ekmett/ai/thc-pinned-addresses-01a0cdeb`, HEAD
`f4805747e0ad7a7612c3b03df9e916a6071d101a` plus the root-owned dirty implementation.
Scope is the six primitives in `20260924T034500Z-pinned-address-memory-scope.md`.
No build, JVM, native executable, GC experiment or capture was run for this review.
The root was implementing corrections concurrently; hashes below identify the final
files read, not a claim that the entire dirty tree is a committed checkpoint.

## Findings and corrections

1. The initial auditor accepted three malformed continuations with **zero issues**:
   a direct State lambda returning Void with an Int result annotation; a known global
   continuation taking Int instead of State; and an under-applied `[State, Int]`
   continuation annotated as returning Int rather than a lifted function. The runtime
   rejected all three by its explicit validator. Pure-Python synthetic Core confirmed
   these auditor false accepts. The root added known lambda/global/alias/PAP signature
   resolution, exact result comparison, and partial-application result checks.
2. Initially bytecode lowering forced/checked the continuation before entering the
   keepAlive operation. Consequently that force was outside its `try/finally` fence
   and before the operation's malformed-State carrier check; AST performed it inside.
   The corrected bytecode operation accepts a lazy callback object and performs
   `Force`/`RequireClosure` inside `try`, after `requireState` (BytecodeRoot.java
   403–428 at review). This is a source-confirmed correction; runtime regression
   execution remains the integration owner's gate.
3. The initial keepAlive validator sent all aggregates to `TupleShape.validate`,
   rejecting already-supported sum results while the auditor's generic sum validator
   allowed them. The root now selects `SumShape.validate` for sums and retains the
   existing tuple/sum restrictions. Unknown scalar result proofs also now fail closed.
   This does not add vector, nested-sum, sum-input, capture or heap transport support.

After corrections, six pure-Python controls passed: valid State→Int, legal partial
State→function and an existing sum result in a non-host nested function were accepted;
the three malformed cases above were rejected. These are auditor checks, not guest
execution or compiled-coverage evidence.

## Source-level checks without a new blocker

- ManagedAddress retains its backing array strongly, preserves aliased views, and
  checks displacement against full-width remaining bounds before addition/narrowing.
  One-past addresses cannot be dereferenced; literal writes fail without mutation.
  Mutable bytes are separate from the compilation-final literal backing.
- Allocation/read/write evaluate State before allocation or memory effects and before
  publishing the result. Alignment is a positive-power-of-two property of managed
  address space, not physical JVM pinning or a native-pointer guarantee.
- Word8 memory contracts require exact Word8Rep, not WordRep, despite the shared Long
  host carrier. State slots erase only after evaluation; address values retain the
  concrete ManagedAddress carrier through existing scalar paths.
- Kept lifted values use the ordinary lazy argument path. Neither backend forces that
  value. Non-tail continuation dispatch and its trampoline remain inside the fence;
  normal return and thrown exceptions both execute `reachabilityFence`.
- Scalar keepAlive results are marked evaluated because existing function roots return
  WHNF: AST FunctionBody evaluates its body and bytecode root construction forces an
  unevaluated scalar result. This is not a blanket strengthening of tuple payloads;
  their own evaluatedness proofs remain unchanged.

## Independent regression source delivered

Commit `170c9a4` on `codex/pinned-address-fixtures` in
`/home/ekmett/ai/thc-pinned-address-fixtures-01a0cdeb` adds **only**
`src/test/kotlin/thc/runtime/CoreKeepAliveTest.kt` (203 lines, seven tests).
It covers exact scalar/tuple/sum proof identity, six bad known signatures, lazy kept
bottom, forcing of a lifted scalar callback result, an unforced lifted tuple field,
executed sum consumption, legal partial application, malformed-State precedence over
callback forcing, exceptions and recovery, and zero outstanding handoff loans.
`git diff --check` passed. **The new Kotlin tests were not compiled or run by this
worker**; root owns their first compile/test run. No GC-timing or physical-address
claim is made. Public Storable/Fingerprint closure remains the separately labelled
frontier/native-only fixture, not a runtime pass established by this review.

## Final inspected file hashes (SHA-256)

| File | Hash |
|---|---|
| src/main/kotlin/thc/runtime/KeepAlive.kt | 6e7386790c1c414a789f815fb3422e6b80a6d66ab630476b0b028be23e54c5d2 |
| src/main/kotlin/thc/runtime/PinnedMemory.kt | 8d2356ccc1bee85d1a33bc81c9dcd9160414c7b3bb14254cc6e1f71f713cf534 |
| src/main/kotlin/thc/runtime/LiteralAddresses.kt | 109019997ccc7f20a6bb1bdebf8883d93cc208458b6c064117155212281dd14b |
| src/main/kotlin/thc/runtime/BytecodeProgram.kt | c65dbe42c503cc02f89485f2f5053bedef99c13421675f0bd61b548cd0ac808b |
| src/main/java/thc/runtime/BytecodeRoot.java | 28e692614a7e4fd8f7c273e34d08c9c98e33900d3745fd3dd5641d2888c05225 |
| scripts/audit-core.py | 3333131dc54c76ef3faa36db29d85f38146a1d733b546df64f849f9015fafaf3 |
| scripts/core-capabilities.json | bbc7bf74da0af206c8a89a9c9c052a75398fe250c06a9e55691ecc3dcde868a0 |

MD5 implementation, full source-export closure and full CI were not reviewed here.
