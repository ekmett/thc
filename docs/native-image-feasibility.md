# Native Image

THC provides two experimental Native Image workflows on the pinned GraalVM
toolchain:

- The [selected-Core native code cache](native-code-cache.md) compiles selected
  guest code ahead of execution. `bin/native-cache build/store/run` produces a
  native launcher and matching machine-code cache. Each fresh run loads that
  cache with guest compilation disabled and rejects runtime lowering or
  interpreted guest entry. It admits synchronous AST code with numeric scalars,
  boxed data, closures, higher-order calls and recursive loops. Tuples, sums,
  vectors and zero-width values use the existing typed calling convention.
- The pure interpreter recipe below packages the Java interpreter in a native
  executable and loads Core at launch. Its explicit guest compilation diagnostic
  remains unsupported.

Neither workflow is a general Haskell executable distribution. The code cache
does not yet admit bytecode, async delivery, IO or FFI. Its numeric CLI entry
can exercise typed internal calls; direct typed public-host persistence remains
to be qualified. Sulong execution and the full executable/resource lifecycle still need native
image support and validation. See the cache guide for exact admission,
platform requirements and the experimental preparation overlays it uses.

## Build and run the pure interpreter

Use GHC 9.14.1 and GraalVM 25.3.4.1 / JDK 25, with `JAVA_HOME` selecting the
pinned JDK and its `native-image` command. From a built THC checkout:

```sh
./gradlew --max-workers=2 installDist
./bin/export-core.sh src/examples/THC/Fixtures.hs
bash bin/native-image-pure.sh
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.Test.json,build/core/THC.Fixtures.json sumLoop 100
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.Test.json,build/core/THC.Fixtures.json caseList 20
```

These commands load the exported Core files at launch; the image contains no
frozen guest program. The expected pure results are `5050` and `210`.
The default backend is bytecode. Put `-Dthc.backend=ast` before the Core paths
to select AST, and `-Dthc.handoffSlabs=true` to select dense handoff storage.

The [probe script](../bin/native-image-pure.sh) reads the installed runtime
JARs, deliberately excludes LLVM/NFI dependencies, and uses the checked
[initialization inventory](../bin/native-image/pure-initialization.txt).
It limits the image builder to an 8 GiB heap and two compiler threads.
An optional script argument selects the output executable path.
On a shared development host, hold its existing build-directory resource lease
around installation and image construction.

Generated Truffle DSL field-access descriptors are prepared at image build
time because the pinned image implementation replaces their reflective fields
with native offsets. Guest contexts, native resource owners and the signing
key are not initialized by this inventory. The pure recipe does not use the
larger diagnostic graph-preparation lists from the investigation.

## Compilation and lifecycle limits

Adding `--compile` after the integer invokes the separate explicit guest
compilation diagnostic. It currently fails with frame-materialization/inlining
bailouts; it does not silently substitute interpretation for successful guest
compilation. A valid compiled target and successful first installed call remain
required before claiming native-image guest JIT support.

The pure classpath cannot establish Sulong, foreign callbacks, native-resource
cleanup or complete executable startup/shutdown support. JVM tests of those
facilities do not substitute for native-image execution checks.

## Planned work

Extend reusable boxed construction to typed fields and constructor partial
applications while preserving per-load ownership and CAF state. Qualify direct
typed public-host persistence through the existing host ABI. Async execution,
foreign calls and the full executable lifecycle need
image-side support for suspension, callbacks and native-resource cleanup.
The pure recipe separately needs successful first installed guest calls before
it can support runtime compilation.

The [open design questions](../research/open-questions.md#native-image-beyond-pure-interpretation)
track wider cache admission, runtime compilation and executable lifecycle support.
