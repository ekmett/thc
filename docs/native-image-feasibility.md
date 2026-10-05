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
./bin/export-core.sh t/fixtures/core/Fixtures.hs
bash bin/native-image-pure.sh
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.Test.json,build/core/Fixtures.json sumLoop 100
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.Test.json,build/core/Fixtures.json caseList 20
```

These commands load the exported Core files at launch; the image contains no
frozen guest program. The expected pure results are `5050` and `210`.
The default backend is bytecode. Put `-Dthc.backend=ast` before the Core paths
to select AST, and `-Dthc.handoffSlabs=true` to select dense handoff storage.

The [build script](../bin/native-image-pure.sh) reads the installed runtime
JARs, deliberately excludes LLVM/NFI dependencies, and uses the checked
[initialization inventory](../bin/native-image/pure-initialization.txt).
It limits the image builder to an 8 GiB heap and two compiler threads.
An optional script argument selects the output executable path.
On a shared development host, hold its existing build-directory resource lease
around installation and image construction.

## Compilation and lifecycle limits

Adding `--compile` after the integer invokes the separate explicit guest
compilation diagnostic. It currently fails with frame-materialization/inlining
bailouts; it does not silently substitute interpretation for successful guest
compilation. A valid compiled target and successful first installed call remain
required before claiming native-image guest JIT support.

The pure classpath cannot establish Sulong, foreign callbacks, native-resource
cleanup or complete executable startup/shutdown support. JVM tests of those
facilities do not substitute for native-image execution checks.

## Preinitialized runtime state

THC supports Truffle's preinitialized-context handoff on the pinned runtime.
Preparation retires its Env-bound services before capture. When Truffle patches
that context, THC creates fresh runtime services from the new permissions,
arguments and streams while preserving the language instance. Per-carrier cells
are ready before the patch hook, because Truffle enters the runtime carrier first.
Preparation requires native access to remain disabled; native linking belongs to
the runtime context.

The isolated `ContextOwnershipTest` uses Truffle's actual JVM preinitialization
entry point and rejects silent fallback to a fresh language. It covers the runtime
handoff and failed-startup cleanup in both handoff modes. The experimental
application-bound recipe now captures its selected entry and shutdown dependencies,
lowers them in this hook with one preparation worker, and discards the Core bodies.
Runtime loads use the saved AST factory under its original language and fresh
State; there is no runtime-lowering fallback. Ordinary cached-source preparation
keeps its configured worker count.

`ReusableLoaderTest` runs the saved factory after removing its CBD inputs with
runtime Core lowering disabled. This is JVM evidence for preparation and runtime
ownership.

Linux diagnostics complete image generation and execute saved factories through
`NativeExecutable.main`, including entry and shutdown, with their bound CBD
namespace unavailable. The checked mutable-state IO/PAP sample uses the pure
recipe's LLVM/NFI exclusions and heap byte arrays. A checked Unicode
`Text.reverse` application also matches native GHC with LLVM/NFI and matching
Linux native resources retained, using synchronous AST execution and native byte
arrays. Its prepared graph retains the validated GHC target-layout descriptor;
that exact class is included in the audited initialization inventory.

The Text executable runs both in its build directory and after relocation into a
directory containing only the same ELF. It also passes with an initially empty
Graal resource cache. File-access traces show no CBD or manifest reads and no
access to the old resource cache in that run. Sulong and package native libraries
are extracted at runtime; system C, math, zlib, dynamic-loader and GMP libraries
remain dependencies. The loader still probes build-machine GHC library paths
before finding system GMP, so this is not a clean deployment contract or a static
executable.

These results qualify serialization and runtime handoff for the selected programs.
General package/Unix-library coverage, transitive native dependency deployment,
Windows images and guest machine-code compilation remain unqualified and are
tracked in [issue #1060](https://github.com/ekmett/thc/issues/1060).
