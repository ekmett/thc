# Native Image feasibility, 2026-09-26

This is a bounded compatibility investigation from THC
`3f9e3c64fe7caf94f738018a106140f47e7f6251`, on Linux x86-64. It does not establish
a Haskell ahead-of-time compiler. Native packaging of the interpreter, guest
runtime compilation, and program-specific ahead-of-time compilation are separate
deliverables.

**Current checkpoint:** a real native THC executable loads the original exported
Haskell Core and returns native GHC's `5050` for `sumLoop 100` and `210` for
`caseList 20`. Both AST and bytecode backends pass in both handoff modes: eight
checks. The optimizing Truffle runtime is included, but explicit guest compilation
fails with frame-materialization/inlining bailouts. This establishes native
packaging and interpretation, not native guest-JIT execution or guest AOT.
Sulong/FFI and a full Haskell executable lifecycle remain unverified.

## Reproduce the working pure image

The working checkpoint incorporates main `981b360c`, the lazy-fork interpreter
transition, native-file binding isolation and primitive unsigned interop queries.
Use the pinned GraalVM `25.3.4.1` JDK/Native Image and GHC `9.14.1` toolchains
described in the repository setup instructions, with `JAVA_HOME` selecting that
JDK. From the repository root:

```sh
./gradlew --max-workers=2 installDist
./compiler/export.sh examples/THC/Fixtures.hs
bash scripts/native-image-pure.sh
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.json,build/core/THC.Fixtures.json sumLoop 100
build/native-image/thc-pure -Xmx2g build/core/THC.Prim.json,build/core/THC.Fixtures.json caseList 20
```

The [probe script](../scripts/native-image-pure.sh) uses the installed runtime
JARs, excludes LLVM/NFI dependencies deliberately, and reads the exact audited
[initialization inventory](../scripts/native-image/pure-initialization.txt).
The versioned script was then rebuilt independently and passed the same eight
native result checks, rather than merely transcribing the successful command.
It bounds the builder to an 8 GiB heap and two compiler threads. On a shared
development host, put the build and the probe inside the host's existing
build-directory resource lease. An optional script argument selects the output
path. This is an explicit compatibility probe, not a shipping distribution.

The commands above select the default bytecode backend. Add `-Dthc.backend=ast`
before the Core paths for AST, and `-Dthc.handoffSlabs=true` for dense handoff
storage. All four combinations produce both expected results. Adding `--compile`
after the integer exercises the separate explicit-compilation check; all four
backend/workload checks currently fail rather than silently substituting an
interpreter. The first call after successful installation remains a future
acceptance gate.

The original successful build took 114.03 seconds and 3,550,308 KiB peak RSS on
the shared Linux x86-64 host; its file size was 67.25 MiB. These are bounded
compatibility measurements, not isolated startup or throughput benchmarks.
The image contains no frozen guest program: it reads those Core files at run
time. Generated Truffle DSL field-access descriptors must be prepared at image
build time because the pinned image implementation replaces their reflective
fields with native offsets. Preparing those exact descriptors removed the
first executable's runtime `InlineSupport.UnsafeField.declaringClass` failure.
The signing key, guest contexts and native resource owners are not initialized
as part of this inventory. Runtime graph preparation for guest JIT continues
separately; the working interpretation recipe does not use the larger diagnostic
preparation list.

## Earlier build investigation

**First-phase result:** no native executable linked, so native execution,
guest-JIT installation in an executable, executable size and startup remain
unverified. The investigation produced reproducible build blockers, one small
tested source fix, and the staged plan below. Pure scalar and constructor Core
were checked against native GHC on the JVM; Sulong image/FFI execution was not
attempted because the pure image had not built.

**Second-phase result:** an interpreter transition before lazy fork dispatch
installation resolves the `Node.<init>` runtime-compilation assertion. The real
THC image passes analysis and reaches native method compilation, but still
fails on unprepared interop deoptimization methods; no THC executable has
linked. The fix passes 58 thread/thunk/original lazy-fork checks across both
handoff modes, including first-installed compiled execution. A separate minimal
Truffle image returns different interpreter/compiled results (`42`/`43`) and
demonstrates actual installed guest machine code. This is toolchain evidence,
not a successful THC native image or Haskell AOT result. Source fix and detailed
worker evidence are recorded in checkpoint `7ff52868`.

## Language preparation and native bindings

The next source checkpoint incorporates main `939c6487`, including the newer
AST/STM and pinned/native-memory work. The pinned
[`DeoptimizationUtils.createGraphChecker`](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/substratevm/src/com.oracle.svm.hosted/src/com/oracle/svm/hosted/code/DeoptimizationUtils.java#L505)
rejects runtime/deoptimization graphs whose declaring class is not initialized,
and rejects runtime graphs containing a class-initialization check. This prevents
partial evaluation from folding fields before initialization. Merely making a
method reachable in the ordinary executable is therefore insufficient.

Explicit preparation of audited interop receiver classes exposed four concrete
compilation-blocklist paths: export namespace map enumeration/lookup and bulk
ByteBuffer reads. Their host operations now have Truffle boundaries; scalar
buffer access and actual exported guest calls retain their existing paths.
The new namespace test checks exact names, aliases, unknown-member rejection
and rejection from another context. Together with `SulongCbitsTest`, this passes
12 cases across default/dense modes.

`NativeFileLease` previously resolved its native close handle in its class
initializer. Its receiver class now contains only a stateless companion;
native bindings live in a separate private holder first accessed when a real
lease is constructed, before allocating its arena. Descriptor/arena ownership,
capture-state handling, close ordering and readiness duplication are unchanged.
`NativeFileProviderTest`, `NativeFdWaitTest` and `NativeFileBuffersTest` pass all
52 cases across the two modes. Bytecode inspection confirms that preparing the
receiver class no longer resolves a native handle.

The runtime-graph encoder next failed while preparing `ManagedAddress.toNativeBits`:
two `ImageHeapConstant` values had no backing hosted constant. A targeted graph
dump identifies the stateless `StablePointers` and `NativeAddresses` companions,
created by Native Image's class-initializer simulation. Explicit initialization
of those two classes and companions, after inspecting their initializers,
removes this failure without disabling simulation globally.

The resulting analysis exposed arbitrary-precision arithmetic in `UnsignedWord64`
interop queries. Signed range checks now use the primitive bits; binary32/64
exactness counts the span between the highest and lowest set bits. Exact values
in the unsigned upper half convert by shifting the zero low bit and doubling.
The BigInteger result representation is unchanged. An independent BigInteger /
BigDecimal model checks 196 boundary values, exact conversions and rejection of
lossy conversions. Host display formatting and native buffer projection also
receive explicit Truffle boundaries; typed scalar buffer operations remain on
their existing paths. Scalar, buffer, pinned-storage and namespace tests pass
50 cases across the two handoff modes.

The next optimizing build completes analysis with all compiler assertions and
blocklist checks enabled, then fails during native method compilation because
`Intrinsics.areEqual` and `ManagedAllocation.getSize` lack prepared deoptimization
variants. This is still not a linked image or a successful Haskell/native result.
Larger preparation lists must come from an initialization audit, not a
package-wide build-time override. New raw diagnostics remain local; this
checkpoint publishes source and concise results only.

## Execution models

| Product | What is fixed when built | Guest execution |
| --- | --- | --- |
| Generic native THC launcher | Java/Kotlin runtime and included language implementations | New Core is loaded and lowered at run time; interpretation plus Truffle JIT when the optimizing runtime is included |
| Application package with frozen Core | Above, plus selected exported Core, package identities and native dependencies | Freezing Core as a resource alone still leaves loading/lowering and guest execution at run time |
| Guest-specific AOT executable | Guest program is lowered/specialized into the executable's machine code before launch | Requires a separate demonstrated compilation pipeline; neither resource embedding nor Native Image's compilation of the interpreter establishes this |

The pinned `truffle-runtime` JAR requests `--macro:truffle-svm`,
`com.oracle.svm.truffle.TruffleFeature`, and
`com.oracle.svm.truffle.api.SubstrateTruffleRuntime` in its native-image properties.
The actual builder enables the feature described as “Provides internal support
for Truffle runtime compilation.” This is not intrinsically an interpreter-only
deployment. The [25.3 embedding documentation](https://www.graalvm.org/jdk25.3/reference-manual/embed-languages/)
also documents explicitly selecting the fallback runtime at image build time.
The feasibility experiments below distinguish feature registration from a guest
compilation actually succeeding.

`RootNode.prepareForAOT` is a preparation hook for compiling a root before its
first execution, including frame and specialization initialization. The pinned
default returns `null`; it is not a command that turns arbitrary embedded Core
into an application executable. THC does not override this hook. A future
guest-AOT milestone must identify and test an actual compilation/export path.

More decisively, the pinned
[`SubstrateTruffleRuntime.submitForCompilation`](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/api/SubstrateTruffleRuntime.java#L294)
returns `null` when `SubstrateUtil.HOSTED` is true. Its implementation deliberately
does not JIT-compile guest code during image generation. Executing guest code
while building can create data, but this implementation does not turn that into
persisted guest machine code. At image run time the same method initializes the
compiler and submits or performs actual guest compilation.

## Toolchain and evidence

- Oracle GraalVM `25.3.4.1+1.1`, Java `25.0.4.1+1-LTS-jvmci-25.3-b22`.
- `native-image 25.0.4.1`, Substrate VM Oracle GraalVM `25.3.4.1+1.1`.
- GHC `9.14.1`, configured full-Core installation; GCC `11.4.0`.
- All Graal/Truffle/Sulong JARs are `25.3.4.1`.
- Builder source revision from the installed release file:
  `7b025988a922a73286d1326e1eddc1ca39d3f569`.
- Native builds use an owned build-directory resource lease, an 8 GiB builder
  heap and two compiler threads, coordinated with the performance workers.
- Original JARs and two genuine exported Core modules came from the performance
  worker's frozen exact-base build, with its SHA-256 manifest retained. The
  source compatibility experiment is rebuilt in this worktree.

Local evidence lives under `build/native-image/`: `logs/`, `images/`, `sources/`,
`core/`, `lib/`, the independent native GHC oracle, and the baseline manifest.
The [committed evidence bundle](../bench/results/native-image/2026-09-26/README.md)
retains the logs, image-analysis reports, test XML and input/artifact hashes.
Native GHC produces `5050` for `sumLoop 100` and `210` for `caseList 20`; the
unchanged JVM scalar launcher also produces `5050`. The rebuilt pure classpath
with array-based StaticShape storage produces `210` for `caseList 20` on the JVM.
The attempted image uses the ordinary `thc.MainKt` file-loading entry point;
it targets generic run-time Core loading, not build-time guest freezing. No Core
executed inside a native image because image construction failed.

## First observed blockers

1. An initial minimal classpath incorrectly omitted `jniutils`, required by
   `org.graalvm.truffle`. Keeping it resolves module discovery. The deprecated
   `--no-fallback` option is ignored by this release and was removed.
2. Unmodified THC fails image analysis because generated `thc.LanguageProvider`
   is retained by Truffle's `LanguageCache` image heap but defaults to run-time
   initialization. The inspected generated provider has no fields or static
   initializer; its constructor only calls the base constructor. The experiment
   adds only `--initialize-at-build-time=thc.LanguageProvider`. It does not
   initialize the language, contexts or all of `thc` at build time.
3. That reveals the next error in `DataValues.kt`, `DataLayout.buildShape`:
   `parameter is not a compile time constant: parameter superClass`.
   `StaticShape.Builder.build(Class, Class)` requires its superclass and factory
   tokens to be constants at each call site. The old expression passes a
   conditional choice of two fixed classes. The experiment splits it into two
   calls with literal tokens, preserving both allocation strategies and the
   allocation authentication checks.

The first substantive attempt took 13.96 seconds and 1,603,064 KiB maximum RSS;
the provider-only retry took 22.35 seconds and 1,706,628 KiB. Both failed, so
neither has executable-size or startup measurements.

The split-shape source rebuild passes `ClassOwnedLayoutTest` in both
`testDefault` and `testDense`: five tests per mode, no failures/skips. The next
optimizing-image attempt gets past shape registration, but fails after 42.44
seconds / 2,786,388 KiB with:

```text
CompilerAsserts.neverPartOfCompilation reachable for runtime compilation
called from: com.oracle.truffle.api.nodes.Node.<init>%%R()
runtime trace:
 Unknown
```

The compiler assertion and blocklist checks remain enabled. This establishes a
THC optimizing-image integration blocker, not that Truffle Native Image lacks a
JIT. The initial diagnostic does not identify which THC call path reaches the
node constructor, so no specific source method is blamed without more evidence.

The separately named fallback-runtime build reaches another initialization
issue: `CbitsBufferGen$InteropLibraryExports` is retained in
`LibraryFactory.ResolvedDispatch.CACHE`. Its generated registration object,
cached singleton and uncached singleton have no guest state. The same generated
pattern occurs in eleven export classes. The next probe explicitly initializes
those audited generated classes and nested export implementations; it does not
initialize the handwritten receiver types or an entire package.

Adding that generated-class inventory did not resolve the optimizing error.
A second diagnostic control initialized the two generated dispatch nodes and
their stateless companions after inspecting their singleton initializers. It
also failed with the same assertion. Even with
`-H:+PrintRuntimeCompilationCallTree -H:+IncludeNodeSourcePositions`, the
offending node constructor was reported with `Unknown` provenance; the call
tree and source-position diagnostics are retained. No assertion or semantic
check was disabled. These expanded initialization lists remain scratch probe
settings, not proposed production defaults.

The fallback control is additionally coupled to THC's optimizing-runtime
references. It first fails an access check for JVMCI `SpeculationLog` while
initializing `OptimizedCallTarget`. Adding that one module export advances it
to a `DefaultAssumption` to `OptimizedAssumption` cast in
`OptimizedCallTarget.ArgumentsProfile`. Deferring that exact profile's
initialization moves the same failure to `ReturnProfile`. Thus forcing the
fallback runtime is not a demonstrated turnkey alternative for this THC tree.
The next focused investigation should isolate the runtime telemetry and
explicit compile API's reachability; it should not keep applying broad package
initialization overrides.

| Probe log | Configuration/result | Wall seconds | Peak RSS KiB |
| --- | --- | ---: | ---: |
| `02-pure-build.log` | Original runtime; provider image-heap error | 13.96 | 1,603,064 |
| `03-provider-init-build.log` | Provider initialized; conditional shape class error | 22.35 | 1,706,628 |
| `06-split-shape-build.log` | Shape calls split; node-constructor compiler assertion | 42.44 | 2,786,388 |
| `08-fallback-build.log` | Intentional fallback; generated export initialization error | 19.66 | 1,662,176 |
| `09-interop-init-fallback-build.log` | Audited generated exports; JVMCI module access error | 19.11 | 1,655,368 |
| `10-interop-init-optimizing-build.log` | Optimizing + exports + method list; same assertion | 46.62 | 2,943,712 |
| `11-fallback-module-export-build.log` | Fallback + JVMCI export; ArgumentsProfile cast | 31.23 | 2,703,000 |
| `12-dispatch-init-optimizing-build.log` | Optimizing + dispatch initialization + call tree; same assertion | 45.48 | 3,013,532 |
| `13-fallback-runtime-profile-build.log` | Fallback + ArgumentsProfile at run time; ReturnProfile cast | 32.16 | 2,698,836 |

These are resource-bounded feasibility runs on a shared host, not isolated
performance benchmarks. The largest resident set was 2.87 GiB. Heavy work stopped
when the performance worker requested a prospective controlled measurement window;
that window was subsequently postponed because another workload remained active.

## Storage and generated code

THC does not directly call `defineClass` or `defineHiddenClass` in its production
sources. Constructor storage ([DataValues.kt](../src/main/kotlin/thc/runtime/DataValues.kt#L73)),
captures ([Frames.kt](../src/main/kotlin/thc/runtime/Frames.kt#L202)), and
handoff slabs ([Handoff.kt](../src/main/kotlin/thc/runtime/Handoff.kt#L64)) use
Truffle `StaticShape`. The Bytecode DSL annotation on
[BytecodeRoot.java](../src/main/java/thc/runtime/BytecodeRoot.java) generates
`BytecodeRootGen` during KAPT; constructing guest bytecode later creates runtime
data using those already compiled Java classes. It is not generating new JVM
classes for every Haskell program.

Pinned `StaticShape.Builder.getStorageStrategy` uses field-based storage on the
JVM but array-based storage by default inside an image. Explicit field-based
selection maps to POD storage inside the image. The matching
[TruffleBaseFeature source](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/substratevm/src/com.oracle.svm.truffle/src/com/oracle/svm/truffle/TruffleBaseFeature.java)
registers the fixed superclass/factory pairs during analysis and supports
run-time shape construction. Property inventories can remain guest dependent;
this does not require enumerating all Haskell constructors in advance.

Do not generalize this into a claim that all dynamic JVM class loading is
impossible in this release: the pinned
[runtime-class-loading documentation](https://github.com/oracle/graal/blob/7b025988a922a73286d1326e1eddc1ca39d3f569/substratevm/docs/runtime-class-loading.md)
describes the opt-in `RuntimeClassLoading` (Crema) mechanism and a separate
runtime-bytecode-JIT option. It has preservation and deployment requirements
and was not enabled or tested here. THC's StaticShape integration uses its
dedicated support instead.

`ClassOwnedLayouts` reserves carrier-class identity permanently and
`DataLayout` already falls back to `LayoutDataValue` for shared carriers.
Consequently the JVM's distinct-class, compact-object layout and allocation
measurements cannot simply be claimed for an image. Array/POD behavior and
context isolation need their own checks; do not remove the owner fallback.

## Reachability, initialization and contexts

The generated `LanguageProvider` is registered by its generated service entry.
Truffle's packaged image features handle language discovery. THC currently has
no production native-image configuration of its own.

[Language.State](../src/main/kotlin/thc/Language.kt#L383) owns threads, STM, native allocations, files, foreign roots,
signals, caches and the exclusive context's assumptions. These are run-time
objects. [CoreModules](../src/main/kotlin/thc/Language.kt#L52) also generates a random HMAC capability key; freezing it
with blanket build-time initialization would be wrong. Native handles, arenas,
worker threads, random state, contexts and mutable guest CAFs must not become
application image-heap state by accident.

The [explicit scalar compilation member](../src/main/kotlin/thc/Language.kt#L714) reflectively calls
`OptimizedCallTarget.compile(boolean)`, `isValidLastTier`, and the runtime's
`bypassedInstalledCode` hook. The last lookup uses the runtime class rather than
a constant class token and needs particular attention in an image. The base
`OptimizedTruffleRuntime` hook is a no-op; HotSpot provides special behavior.
Runtime telemetry also assumes the pinned optimizing runtime and JVM management
services; support must be reported from the actual native runtime, not inferred
from a linked class.

## Sulong and native boundaries

`Language` declares dependent language `llvm`; the normal distribution includes
`llvm-community`, its native implementation/resources, and Truffle NFI. The
first image intentionally omits those JARs to isolate pure THC. This is a probe
configuration, not a proposed full distribution.

`SulongCbits` loads dynamically named `/thc/cbits/*.bc` resources and checks the
platform manifest. `PackageScalarLibraries` parses verified package bitcode at
run time. `SulongLimbProvider` loads the GMP adapter with real native GMP.
`NativeFileProvider`, signal and atomic helpers extract embedded native libraries.
Production image configuration must retain these resources and package their
platform dependencies. Truffle's own internal language resources have built-in
image support; THC's dynamic resource-name construction is a separate inventory.
The pinned LLVM JAR supplies its own package initialization configuration;
the pinned libffi NFI JAR registers Native Image NFI and OS-specific features.
These are actual artifact capabilities, not evidence that THC's complete native
dependency closure has executed successfully in an image.

Native Image 25.3 documents foreign memory support and FFM down/upcall support
for Linux x64/AArch64, macOS AArch64 and Windows x64. Runtime call descriptors
need build-time registration, including call-state capture and variadic options.
This affects CPU affinity, `NativeFdWait`, signals, open/file helpers and
`ManagedNativeAllocations`; adding native-access permission alone is insufficient.
See [the versioned FFM guide](https://www.graalvm.org/jdk25.3/reference-manual/native-image/native-code-interoperability/ffm-api/).

The repository's ordinary Sulong mode can call native libraries and use native
memory. [Strict managed LLVM mode](https://www.graalvm.org/jdk25.3/reference-manual/llvm/NativeExecution/)
is a separate Oracle distribution feature with different restrictions and
bitcode target assumptions. It is not what `llvm-community` supplies, and it is
not a prerequisite for producing a native THC executable. This investigation
does not change ForeignPtr policy, allocation ownership, pointer representation
or the C ABI.

## Staged acceptance plan

1. Make generic image packaging work with the narrow initialization and static
   shape changes, retaining failures as reproducible evidence. Check real scalar
   and allocation Core against independent native GHC for both backends.
2. Verify guest JIT installation and execution inside that image with original
   result checks. Keep an intentional interpreter-only image as a separately
   named control, not evidence of a compiled guest.
3. Add Sulong, THC resource retention and a finite audited FFM descriptor
   inventory. Run an existing original-import/FFI smoke, then the ordinary
   `Main.main` startup/Handle/shutdown path with native output and exit checks.
4. Package one application's exact Core closure, content hashes and required
   libraries. Run it outside the source checkout on a host with no GHC, JVM,
   Gradle or Cabal. Compare stdout, stderr, exit status and file side effects
   against native GHC, including input-dependent allocation and an FFI call.
   This is a standalone native application package with an embedded runtime.
5. Specify guest-specific AOT as a further compiler milestone: choose and
   demonstrate a supported pre-launch specialization/code-emission mechanism,
   preserve laziness, CAF isolation and native lifetimes, and establish that
   guest machine code exists before launch. Measure first-run behavior with
   runtime compilation disabled, alongside the generic JIT package. Only this
   evidence can support the stronger ahead-of-time compiler claim.

For stage 5, the concrete alternatives are a new Core-to-host-code emission
pass (whose emitted methods Native Image can compile), a separate native code
backend, or explicit work on a supported precompiled Truffle guest-code path.
The first two must carry THC's calling convention, lazy updates, exceptions,
thread/STM behavior and FFI ownership into generated code. The third is not
provided by calling the existing compile member during image generation in
the pinned runtime. Choosing among those larger compiler directions belongs
after the much smaller native-runtime packaging proof.

Distribution choice remains explicit: the local probe uses Oracle GraalVM,
while the repository's LLVM dependency is the Community artifact. A Community
builder and other operating systems remain separate verification work; retain
their applicable dependency and distribution licenses.

## Reproduction of the initial attempts

With the pinned GraalVM on `PATH`, build ordinary runtime artifacts first:

```sh
./gradlew --offline --no-daemon --max-workers=2 installDist
./gradlew --offline --no-daemon --max-workers=2 --continue \
  testDefault --tests 'thc.runtime.ClassOwnedLayoutTest' \
  testDense --tests 'thc.runtime.ClassOwnedLayoutTest'
```

The pure image classpath is the distribution `lib/` JAR set excluding `llvm-*`,
`antlr4-*` and `truffle-nfi-*`. Retain `jniutils`. In the evidence directory that
classpath is assembled from copies in `build/native-image/lib/`; no dependency
JARs are modified. The optimizing attempt is:

```sh
native-image -Ob -J-Xmx8g -J-XX:ActiveProcessorCount=2 --parallelism=2 \
  --add-modules=jdk.incubator.vector \
  --enable-native-access=ALL-UNNAMED,org.graalvm.truffle \
  --initialize-at-build-time=thc.LanguageProvider \
  -cp "$probe_cp" thc.MainKt build/native-image/images/thc-pure
```

For the intentional interpreter-only control add
`-Dtruffle.UseFallbackRuntime=true` and use a distinct output name. Full commands,
including exact classpaths and `/usr/bin/time -v` results, are in the retained
logs. Each native invocation is wrapped in the host's build-directory resource
lease. No global Graal configuration is changed.
