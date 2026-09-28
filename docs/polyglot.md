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
cache. Calls remain subject to the other language's thread-access rules,
including virtual-thread access in Loom mode. A `safe` call may re-enter an exposed
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

The complete example is [PolyglotDemo.hs](../src/examples/THC/PolyglotDemo.hs).
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

The lower-level module and storage APIs use versioned `foreign import prim`
symbols, beginning with `thc_polyglot_v1_eval`,
`thc_polyglot_v1_read_member`, and `thc_polyglot_v1_execute_int`.
GHC retains their `State# RealWorld` input and
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

`THC.Polyglot`, `THC.Interop.Buffer` and `THC.Interop.Array` are exposed by
the `thc:interop` library. Add `thc:interop` to the application's Cabal
`build-depends` for THC execution. This component has no native implementation
of its prim symbols and cannot be linked into an ordinary native GHC executable;
the separate `thc:runtime` library remains native-linkable. Raw prim declarations
live in the explicitly Unsafe `THC.Internal.Polyglot` module. Loading another language
in one Polyglot Context also brings that language's thread-access rules:
JavaScript may require serialized or isolated access even when Haskell code
runs concurrently. An admitted callback stays on its carrier and obeys the
other language's access rules; safety is not permission for concurrent entry.

The normal `thc acquire`/`thc run` project path recognizes `thc:interop` in the
selected application's Cabal dependency closure and omits only that application's
native final link. Dependencies and build-tool executables still compile and link
normally; no native implementation of these prim symbols is invented.

For standalone Core export against an installed component, the normal helper
loads the Cabal-built plugin directly, avoiding ordinary `-fplugin`'s eager native
link of guest dependencies. Caller `-fplugin-opt=THC.Plugin:...` options and
`THC_SOURCE_NOTES` retain their usual meaning:

```sh
cabal build lib:interop
package_db=dist-newstyle/packagedb/ghc-9.14.1
interop_unit=$(ghc-pkg --package-db "$package_db" field z-thc-z-interop id --simple-output)
bin/export-core.sh -i -package-db "$package_db" -package-id "$interop_unit" \
  -fplugin-trustworthy -fplugin-opt=THC.Plugin:post-tidy \
  Application.hs
```

Use the pinned compiler that built the plugin. This exports Core; it does not
enable native execution or Template Haskell evaluation of these intrinsics.

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
context rules above. The Core host boundary also exposes signature-checked
closures and partial applications as executable values. Records remain opaque;
blindly forcing their contents would change Haskell evaluation and space behavior.

## Buffers and fixed arrays

`THC.Interop.Buffer.view` aliases a `ByteArray#` as a read-only buffer;
`mutableView` explicitly grants writes to a `MutableByteArray# RealWorld`.
Both retain the allocation. They support byte access and explicit little/big-endian
64-bit access, without requiring native addresses or native byte order.
`THC.Polyglot.execute` passes one opaque value to a foreign function, so views
can be shared with a language that understands the corresponding interop protocol.

```haskell
{-# LANGUAGE MagicHash #-}
import qualified THC.Polyglot as P
import qualified THC.Interop.Buffer as B

snapshot = do
  buffer <- P.evalJS "new Uint8Array([10,20,30,40]).buffer"# "bytes.js"#
  B.copySlice buffer 1 2  -- fresh ByteArray# containing 20,30
```

`copy` and `copySlice` use the bulk foreign-buffer read protocol and return a
lifted `ByteArray` owner. `copyInto` copies a checked source region into existing
mutable guest storage; it snapshots the source first, so overlapping aliases
are safe. Negative, overflowing and out-of-bounds ranges are rejected.
Zero-length transfers at the end are valid. A read-only foreign buffer can
still change through another alias: only an explicit copy creates a snapshot.
Foreign views stay managed foreign handles; ordinary byte-array primops continue
to use ordinary guest allocations.

Opaque `Value` handles may be lazy. Their guest evaluation completes before the
foreign operation starts; suspension retains completed operands and the pending
operation. Resuming a handle does not replay earlier operands or a completed
foreign effect. This also applies to the value passed to `execute` or an array write.

`THC.Interop.Array` exposes fixed `Array#` and `SmallArray#` views, queries
foreign collection sizes, reads individual opaque values, and copies a foreign
collection into an `Array# Value`. It does not force Haskell elements or claim
that arbitrary foreign values have a Haskell element type. The separate
`THC.Interop.Array.Unsafe` module provides `unsafeMutableView` and `unsafeMutableSmallView`, which
grant foreign code replacement authority over polymorphic Haskell storage.
The caller must preserve the element type. Replacements must at least be lifted
guest references from the same context; insertion and removal are unavailable.

Do not unsafe-freeze or repurpose storage while a foreign writer retains a view.
Views cannot cross THC contexts or outlive context closure. Unknown-length
`Addr#` values are not buffers, and managed heap storage never acquires a native
address merely by being exported. Original pointer-cell exclusion and native
allocation lifetime checks still apply.
