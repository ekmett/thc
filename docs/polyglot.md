# Polyglot calls from Haskell

THC accepts synchronous JavaScript imports through its GHC plugin:

```haskell
{-# LANGUAGE ForeignFunctionInterface #-}

foreign import javascript "(x, y) => x + y"
  add :: Int -> Int -> IO Int

foreign import javascript "(x) => x * 0.5"
  half :: Double -> IO Double
```

Run `bin/javascript-demo.sh` for the complete example.

The declaration syntax accepts `Int` and `Double` arguments and
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

The complete example is [PolyglotDemo.hs](../src/examples/PolyglotDemo.hs).
Both demos are ordinary Cabal executables depending on `thc:interop`. With the
[complete-Core GHC toolchain](ghc-core.md) selected, run:

```sh
bin/polyglot-demo.sh --ghc-source /path/to/configured/ghc-9.14.1
bin/javascript-demo.sh --ghc-source /path/to/configured/ghc-9.14.1
```

The scripts use `thc acquire` to build the selected application and link its
dependencies, including the genuine `THC.Exception` support required for foreign
calls. They audit the acquired entry and run it on both THC backends with the
optional GraalVM JavaScript dependency. Each backend prints
`THC polyglot result: 42`. Further acquisition flags are passed through to the
driver. Dependencies and build tools still compile natively; only the selected
guest executable skips its native final link.

[The Java host](../src/examples/java/thc/PolyglotDemo.java) loads the generated
`packages.json` through `Main.loadEntry` and invokes `runIO`. To rerun an acquired
application without rebuilding it:

```sh
./gradlew polyglotDemo --args="build/polyglot/packages.json $(cat build/polyglot/entry.txt)"
```

Example classes and the optional JavaScript dependency are separate from the
runtime JAR. Compiled-entry regression checks live in `AcquiredPolyglotDemoTest`;
the public host needs no compilation controls or runtime-internal calls.

The host must permit the requested language through `PolyglotAccess`; the demo
does so explicitly. THC uses `Env.parsePublic`, so the bridge obeys that policy.

These APIs are THC intrinsics, not a native C ABI. Compiling the module with GHC
does not provide native implementations; use `thc:interop` as described below.

`Value` is opaque to Haskell. THC stores a managed guest reference together
with its owning context; it does not turn a JavaScript object into a raw
pointer or integer handle. Source text, language IDs, source names,
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

### Explicit dispatch

`THC.Prim` provides raw, state-indexed `Object# s` and `InteropLibrary# s`
references. Both are genuinely unlifted boxed values. The latter holds the
actual dispatcher acquired by `getInteropLibrary#`, not a receiver wrapper.
Raw messages take both references and a `State# s`; their unboxed results keep
the declared primitive widths. The operations cover buffer byte
access, array element access, their capability/size queries, and signed 64-bit
conversion.

`THC.Interop` pairs those references in the lifted `Interop s` handle and
provides `ST s` operations. External receivers enter `RealWorld` through
`fromValue`; use `stToIO` to access them from `IO`:

```haskell
{-# LANGUAGE MagicHash #-}
import Control.Monad.ST (stToIO)
import qualified THC.Polyglot as P
import qualified THC.Interop as I

firstByte = do
  value <- P.evalJS "new Uint8Array([10,20,30]).buffer"# "bytes.js"#
  bytes <- I.fromValue value
  stToIO (I.readBufferByte bytes 0)
```

Access invokes the receiver's protocol without copying the collection. Array
elements are heterogeneous; `readArrayElement` returns another handle, not an
unchecked Haskell element type. Unsupported messages and invalid indices use
the genuine foreign-exception bridge described above.

The acquisition site owns bounded dispatcher children. A use site profiles
only the supplied dispatcher's identity and never creates a replacement.
Monomorphic dispatch can specialize after acquisition and profiling;
polymorphic dispatch remains correct without promising inlining. Neither the
state index nor reuse of a dispatcher makes a foreign receiver thread-safe.
The nominal state roles prohibit coercing a `RealWorld` handle into a confined
`ST` region. Raw primitives are in the explicitly Unsafe `THC.Prim` module.
Raw host exports remain opaque: an underlying Java array or closure does not
grant guest storage or callable authority. Genuine Haskell storage views retain
their explicit permissions and owning context.

### Storage views and explicit copies

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

## Lazy Haskell values

A lifted guest value can cross the Truffle boundary without being evaluated.
The existing context-owned `HostReference` holds the actual thunk; exporting,
querying its numeric capabilities and displaying it without side effects do not
force it. Explicit numeric conversion demands its shared result:

```java
Value lazy = guestArray.getArrayElement(0);
long answer = lazy.asLong();
```

All seven numeric conversions (`asByte`, `asShort`, `asInt`, `asLong`,
`asBigInteger`, `asFloat` and `asDouble`) demand only weak head normal form.
A thunk that produces a function is evaluated but that function is not applied;
a nonnumeric result rejects conversion. A pending thunk is also executable:
`lazy.execute()` with no arguments explicitly demands a nonfunction result.

Function-valued thunks retain the existing signature-driven invocation behavior:
the arguments apply to the resulting Haskell function. Ordinary invocation uses
THC's guest-thread admission, async exception handling and memoized update, so
repeated invocation does not repeat the thunk's computation. Context ownership
and lifetime checks remain in the boundary view.

Published builtin signed and unsigned integer, Float and Double constructors
expose Truffle's numeric messages through the existing foreign-export scalar
codec. Conversions preserve exact ranges, unsigned high bits and floating-point
values; a lossy conversion remains unsupported. Unrelated one-field records are
not interpreted as numbers. The language constructor continues to store its
primitive payload; a scalar conversion at the host boundary may box that value.

THC deliberately allows these explicit conversions to evaluate guest code,
extending Truffle's usual side-effect-free conversion contract. Conversions use
the same guest admission, thread hosting, exception translation and shared thunk
updates as invocation. Success and synchronous failure are memoized; converting
again does not repeat the computation. Raw foreign references have no such guest
evaluation authority, and closing the context prevents further conversion.

`isNumber()` and every `fitsIn*()` query remain nonforcing and return false for a
pending thunk. After successful evaluation, those queries observe the published
numeric answer. Clients that first query a capability and decline conversion
when it is false never request evaluation; they must explicitly demand the value
if they want it computed. The nonforcing `Lifted.resolve` and `Lifted.project`
value-resolution protocol is unchanged.
