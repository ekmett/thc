# Native Image

THC has an experimental native launcher for interpreting accepted pure Core.
It packages the Java interpreter with the pinned GraalVM Native Image
toolchain and loads Core files at run time. It is not a shipping distribution
or a guest ahead-of-time compiler.

The optimizing Truffle runtime is included, but explicit guest compilation is
not supported by the working pure-image recipe. Including that compiler,
embedding Core as a resource, or successfully compiling guest code on the JVM
does not establish guest JIT or guest AOT in the native executable.
Sulong/FFI execution and the full Haskell executable/resource lifecycle remain
unverified by this recipe.

The separate [selected-Core native code cache](native-code-cache.md) provides an
opt-in `bin/native-cache build/store/run` workflow for the admitted synchronous
AST scalar family. It stores compiled guest code before a fresh run process;
its pinned platform/toolchain limits do not broaden the pure recipe below.

## Build and run the pure interpreter

Use GHC 9.14.1 and GraalVM 25.3.4.1 / JDK 25, with `JAVA_HOME` selecting the
pinned JDK and its `native-image` command. From a built THC checkout:

```sh
./gradlew --max-workers=2 installDist
./bin/export-core.sh src/examples/THC/Fixtures.hs
bash bin/native-image-pure.sh
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.json,build/core/THC.Fixtures.json sumLoop 100
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.json,build/core/THC.Fixtures.json caseList 20
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

Compiling the interpreter into a native executable is also distinct from
compiling a particular Haskell program ahead of time. Embedding Core files as
resources would fix the input bundle, not remove runtime loading/lowering or
demonstrate guest AOT. THC has no demonstrated guest-specific AOT export pipeline.

The pure classpath cannot establish Sulong, foreign callbacks, native-resource
cleanup or complete executable startup/shutdown support. JVM tests of those
facilities do not substitute for native-image execution checks.

## Planned work

Guest runtime compilation needs compatible graph preparation and first-call
execution checks. Foreign execution and full executable lifecycle need their
own image configuration and resource tests. Guest-specific AOT requires a
separate demonstrated compilation/export path.

The [open design questions](../research/open-questions.md#native-image-beyond-pure-interpretation)
separate compiler preparation from resource-lifecycle and guest-AOT work.
