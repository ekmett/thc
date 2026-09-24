# Polyglot calls from Haskell

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
Run `scripts/polyglot-demo.sh` from the repository root. The script builds the
pinned GHC plugin, exports optimized Core before and after Tidy, audits the
reachable `IO ()` entry, and runs the demo with the optional GraalVM JavaScript
dependency. The Gradle `polyglotDemo` task runs both THC backends. The normal
test runtime does not need JavaScript.

The demo checks `42` in each of these configurations, first interpreted and then
after explicit guest compilation:

```text
pre-core / ast: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
pre-core / bytecode: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
post-core / ast: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
post-core / bytecode: Haskell -> JavaScript -> Haskell = 42 (interpreted and compiled)
```

`scripts/gradle.sh polyglotTest` checks the declaration contract, context ownership,
access permissions, numeric conversion, missing members, and foreign exceptions.
The host must permit the requested language through `PolyglotAccess`; the demo
does so explicitly. THC uses `Env.parsePublic`, so the bridge obeys that policy.

The module uses three versioned `foreign import prim` symbols:
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
typed conversions, multiple arguments and member invocation, and explicit
handling of foreign errors, callbacks, and value lifetimes. The module would
then move from `examples/THC` into a Cabal package. Loading another language
in one Polyglot Context also brings that language's thread-access rules:
JavaScript may require serialized or isolated access even when Haskell code
runs concurrently. That boundary needs a deliberate policy before exposing
callbacks or concurrent foreign calls.

Foreign exceptions currently escape to the polyglot host. Catching them with
Haskell's exception machinery needs an explicit translation, including a policy
for asynchronous exceptions during a foreign call. Callbacks need the reverse
bridge: expose a Haskell closure as an executable Truffle object and marshal its
arguments and result according to a declared signature. Arbitrary records and
collections also need deliberate lazy/strict conversion rules; blindly forcing
their contents would change Haskell evaluation and space behavior.
