# Polyglot calls from Haskell

THC accepts synchronous JavaScript imports through its GHC plugin:

```haskell
{-# LANGUAGE ForeignFunctionInterface #-}

foreign import javascript "(x, y) => x + y"
  add :: Int -> Int -> IO Int

foreign import javascript "(x) => x * 0.5"
  half :: Double -> IO Double
```

Run `bin/javascript-demo.sh` for the complete example. It checks unary,
binary, floating-point, zero-argument, and unit-returning calls using real GHC
exports before and after Tidy, on both THC backends with explicit compilation.

The initial declaration syntax accepts `Int` and `Double` arguments and
`IO Int`, `IO Double`, or `IO ()` results. Both `safe` and `unsafe` synchronous
imports are accepted. Pure imports, `interruptible`, and the
`dynamic`/`wrapper` declaration forms are rejected. The quoted JavaScript must denote an
unapplied function, following GHC's JavaScript backend syntax.

The plugin rewrites the parsed declaration before native GHC rejects the
JavaScript calling convention. GHC supplies the ordinary scalar FFI wrappers;
the resulting Core contains a versioned intrinsic with the exact JavaScript
source and machine representations. THC checks that contract and implements
the call through Truffle. It caches a call wrapper per source/arity and context,
while evaluating the imported function expression on each invocation, so
rebinding a JavaScript global remains observable. Operands and results use
primitive THC slots; the argument array at the Truffle interop boundary is
still required by that API.

Safety remains attached to each call site, independently of the source/arity
cache. The safe transitions and reverse callbacks described here require platform
hosting; Loom rejects them. Unsafe imports remain subject to the other language's
virtual-thread access rules. In platform mode, a `safe` call may re-enter an exposed
managed Haskell entry; an `unsafe` call rejects that entry before creating a guest thread or changing its mask.
The lower-level `THC.Polyglot` operations are safe. Both backends save the exact
typed result before polling for a queued async exception, so continuation resume
does not repeat the foreign effect. Calls remain entered on the same carrier;
this does not introduce raw C callback transport or interrupt arbitrary native
blocking calls. See [async semantics](async-exceptions.md) for callback identities
and the remaining native errno transition.

These declarations run under THC. Their generated symbols have no native
implementation for linking an ordinary GHC executable. The plugin also rejects
source-level `ccall` declarations that try to use the reserved JavaScript marker.

`THC.Polyglot` is a small Haskell module that lets code running in THC call
another language in the same GraalVM Polyglot Context. The current example
evaluates JavaScript, reads a function member, calls it with an `Int`, and
passes the returned `Int` to a second JavaScript function. The Haskell code
checks the final value, and the JavaScript function prints `42`.

```haskell
{-# LANGUAGE MagicHash #-}
import THC.Polyglot

main :: IO ()
main = do
  object <- evalJS "({ add: x => x + 7 })"# "example.js"#
  add <- readMember object "add"#
  answer <- executeInt add 35
  if answer == 42 then pure () else do
    _ <- evalJS "throw new Error('wrong answer')"# "failure.js"#
    pure ()
```

The complete example is [PolyglotDemo.hs](../examples/THC/PolyglotDemo.hs).
Run `bin/polyglot-demo.sh` from the repository root. The script builds the
pinned GHC plugin, exports optimized Core before and after Tidy, audits the
reachable `IO ()` entry, and runs the demo with the optional GraalVM JavaScript
dependency. The Gradle `polyglotDemo` task builds the separate `src/examples/`
source set and runs both THC backends. Example classes are not included in the
runtime JAR, production distribution, or JVM API reference. The normal
test runtime does not need JavaScript.

The demo checks `42` in each of these configurations, first interpreted and then
after explicit guest compilation:

```text
pre-core / ast: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
pre-core / bytecode: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
post-core / ast: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
post-core / bytecode: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
```

`./gradlew polyglotTestDefault polyglotTestDense` checks the declaration contract, context ownership,
access permissions, numeric conversion, missing members, foreign exceptions,
callback safety and first-compiled completed-result delivery without replay.
`polyglotTest` remains available for a single default invocation.
The host must permit the requested language through `PolyglotAccess`; the demo
does so explicitly. THC uses `Env.parsePublic`, so the bridge obeys that policy.

This lower-level module uses three versioned `foreign import prim` symbols:
`thc_polyglot_v1_eval`, `thc_polyglot_v1_read_member`, and
`thc_polyglot_v1_execute_int`. GHC retains their `State# RealWorld` input and
unboxed state/result tuple output, so optimized Core still represents the
effects in order. The exporter records GHC's actual foreign-call declaration,
including its target, convention, safety, saturation, and machine
representations. THC links only the audited v1 signatures. These symbols are
THC intrinsics, not a native C ABI: compiling the module with GHC does not
provide a native implementation of them.

For example, `executeInt` wraps this declaration:

```haskell
foreign import prim "thc_polyglot_v1_execute_int"
  executeInt# :: Any -> Int# -> State# RealWorld
              -> (# State# RealWorld, Int# #)
```

The symbol names the bridge operation. The first argument selects the foreign
function at runtime. THC lowers the call to a cached `InteropLibrary.execute`
message and writes its checked integer result into a primitive result slot.
GHC needs no patch or new calling convention for this route.

`Value` is opaque to Haskell. THC stores a managed guest reference together
with its owning context; it does not turn a JavaScript object into a raw
pointer or integer handle. For now, source text, language IDs, source names,
and member names must be NUL-terminated UTF-8 `Addr#` literals. `executeInt`
accepts an input within JavaScript Number's exact integer range,
±(2^53 − 1), and requires a result that fits exactly in a signed 64-bit
integer. Calls can fail if a language is unavailable, a member is missing,
or an interop operation rejects the value.

The next useful API steps are ordinary `Text` or byte-buffer inputs, more
typed conversions, multiple arguments and member invocation, and richer
value-lifetime controls. The module would
then move from `examples/THC` into a Cabal package. Loading another language
in one Polyglot Context also brings that language's thread-access rules:
JavaScript may require serialized or isolated access even when Haskell code
runs concurrently. An admitted callback stays on its carrier and obeys the
other language's access rules; safety is not permission for concurrent entry.

With the genuine runtime bridge linked, admitted foreign runtime and parse
failures are automatically catchable as `THC.Exception.ForeignException`, including
through `SomeException`. Compatible Polyglot and managed-export exits restore the
original foreign object after an ordinary rethrow. Missing or ambiguous bridge
support is a load/link error. Cancellation, internal control transfers and fatal
host failures are not converted; ABI errors and native errno APIs retain their
own handling. See [foreign exceptions](foreign-exceptions.md) for exact eligibility
and stored-rethrow provenance. This is not exception unwinding across a plain C ABI.

[Managed exports](site/embedding.md#call-a-declared-haskell-export) expose declared
scalar Haskell functions through Truffle interop, subject to the call-safety and
context rules above. Arbitrary closures, records and collections still need
deliberate argument/result and lazy/strict conversion rules; blindly forcing
their contents would change Haskell evaluation and space behavior.
