# Synchronous exception fixtures

This is native-GHC and exported-Core evidence for the next Handle/error slice,
not an implementation or capability promotion. It adds no runtime, auditor,
exporter, source-overlay, or CI changes. In particular, it does not make
`catch#`, `raiseIO#`, masking, or complete Handle IO executable in THC.

The fixtures use genuine GHC 9.14.1 Core from the ordinary Cabal-built plugin.
They exercise lifted exception payloads and lifted boxed results only; the
primops' full levity/runtime-representation polymorphism is outside this slice.

## Cases

Each root accepts an `Int#` and returns an `Int#`. Arithmetic wraps at 64 bits.
The fixture deliberately exposes the interesting operation through `OPAQUE`
roots, not through hand-written Core or a substituted library implementation.

| Root | Native result for input `x` | Boundary exercised |
| --- | --- | --- |
| `preciseCatch` | `x + 17` | Precise `raiseIO#` transfers to the handler. |
| `actionHeadCatch` | `x + 19` | The catch frame protects evaluation of the action closure itself. |
| `ignoredBottomPayload` | `x + 23` | An ignored exception payload is not forced. |
| `nestedRethrow` | `(x + 29) * 3` | A handler's rethrow reaches the outer handler, not itself. |
| `unusedHandler` | `x + 31` | A successful action does not evaluate its bottoming handler. |
| `lazyResultBoundary` | `x + 37` | Demanding a lazy result after inner catch returns raises outside that frame. |
| `restoreAndRethrow` | `258 * x + 41` | Restore the original MVar contents before rethrowing; failed `tryPutMVar#` leaves its bottom payload lazy. |
| `handlerMaskState` | `34` | Observe unmasked before/after and masked-interruptible during the handler. |

The last case encodes native primitive masking states as
`before + 17 * during + 257 * after`: `0 + 17 * 2 + 257 * 0 = 34`.
It is a separate unsupported masking frontier. Native catch's masking behavior
is observable even when the thrown exception is synchronous; a future runtime
implementation must not advertise complete catch semantics while ignoring it.

## Reproduce

Use the repository's pinned GHC 9.14.1, Python 3.12+, and Cabal toolchain. The
preparer invokes `compiler/build.sh`, reads `build/compiler/plugin.json`, and
passes its actual `unitId` and `packageDb` to GHC. It never synthesizes a package
record or assumes a versioned plugin unit name. Normal `GHC`, `GHC_PKG`, `CABAL`,
and Cabal configuration settings still apply.

```sh
python3 scripts/test-synchronous-exception-fixtures.py
python3 -O scripts/test-synchronous-exception-fixtures.py
python3 scripts/prepare-synchronous-exceptions.py
python3 scripts/prepare-synchronous-exceptions.py --check-only
```

Preparation requires a fresh output directory. Use `--out build/another-name`
for a new attempt; previous failure logs are not overwritten or silently
repaired. `--check-only` is read-only and invokes no compiler or native process.
It checks the exact input/artifact inventory, hashes, retained command exits,
audit summaries, all oracle rows, and the independent arithmetic model.

Generated evidence includes pre/post-Tidy Core, one strict closure audit per
root and stage, observed primop representation contracts, native TSV output,
the native executable, command/stdout/stderr records, and a provenance manifest.
The native executable confirms a 64-bit `Int` before the 169 edge/seeded inputs
are used. All eight roots yield 1,352 native/model comparisons.

Source and task-owned generated artifacts are hashed. Installed GHC libraries
and other installed toolchain files are not hashed. Rebuilding a plugin or
changing a source dependency can invalidate a retained preparation; use a fresh
directory rather than changing its manifest to match new files.

## Checked checkpoint and remaining gaps

On Linux x86_64, the initial root-Cabal integration base `169b791ecceca7b3b48bbaa9c4ec2c556163436e`
produced 1,352 matching native/model rows, 42 observed exception application
contracts across both stages, and zero missing Haskell globals in all 16
closure audits. Fourteen fixture/model/provenance unit tests passed normally
and under Python `-O`. Both read-only provenance checks passed.

All 16 strict audits remain rejected at that base, deliberately. Their only
issues are the retained unsupported primitives: `catch#`, `raiseIO#`, the five
MVar operations used by restoration, and the two masking operations used by
`handlerMaskState`. The preparer requires each root's intended primitive set to
survive optimization and rejects other issue kinds or missing globals. It can
also record accepted roots when the corresponding capabilities are implemented
later; the fixture itself does not change the capability table.

This base predates the separate managed-MVar feature, so its restoration audit
still reports those MVar operations as unsupported. Native source-level
restoration is not a claim that THC already executes that combined path.
There are no THC JVM executions or compiled-runtime claims in this checkpoint.

Future runtime work must preserve guest-exception identity/laziness, keep the
handler outside its own catch boundary, distinguish guest failures from host
faults/cancellation, preserve logical `State#` arguments/results, and implement
or explicitly reject the missing masking semantics. Async delivery, `throwTo`,
interrupted-thunk resumption, arbitrary result representations, exception
contexts, weak finalizers, FFI, and complete Handle IO remain outside this work.

## Source basis

The semantic reference is GHC commit
`902339d332fb4ce2b3c87dcac1ee6495d41ad886` (9.14.1):

- [Primop types and catch strictness](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/compiler/GHC/Builtin/primops.txt.pp#L2732)
  distinguish precise `raiseIO#` from imprecise `raise#` and explain catch's
  action demand.
- [RTS catch/raise implementation](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/rts/Exception.cmm#L406)
  installs the catch frame before applying the action, removes it before the
  handler, and passes the exception without evaluating its payload.
- [Strict IO wrappers](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/ghc-internal/src/GHC/Internal/IO.hs#L149)
  force the action outside the primitive catch. The action-head fixture tests
  `catch#`, not those head-strict wrappers.
- [Handle restoration](https://github.com/ghc/ghc/blob/902339d332fb4ce2b3c87dcac1ee6495d41ad886/libraries/ghc-internal/src/GHC/Internal/IO/Handle/Internals.hs#L144)
  combines masking, take/put, catch, and rethrow. The fixture isolates only its
  synchronous restoration shape; it is not async-safe `bracket` or a Handle port.
