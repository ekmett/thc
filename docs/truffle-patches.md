# Truffle patches and shared hosts

THC selects [JAM-patched GraalVM 25.3.4.1 / JDK 25](jam-runtime.md). Its
default distribution additionally replaces three Truffle/Sulong dependency
JARs with locally built variants. **These replacements affect every language using those loaded classes,
not just THC.** A separate Polyglot `Context` or `Engine` does not select a
different implementation of a shared runtime class.

The build compiles the changed upstream Java classes and repackages their JARs.
It does not build or modify the JDK, Graal compiler or Truffle DSL processor, and
does not overwrite artifacts in shared Maven/Gradle caches. Distinct filenames
identify the replacements; the classes retain their upstream packages and module
identities. The selected runtime is a host integration decision, not a context
option.

## Replaced artifacts

All upstream inputs are pinned to **25.3.4.1**. Nested classes belonging to the
recompiled classes are replaced as needed too.

| Upstream artifact | Default replacement | Recompiled classes |
| --- | --- | --- |
| `org.graalvm.truffle:truffle-api` | `thc-truffle-api-25.3.4.1-protocol3-carrier1.jar` | `com.oracle.truffle.api.nodes.RootNode`, `com.oracle.truffle.api.nodes.NodeAccessor`, `com.oracle.truffle.api.bytecode.ContinuationRootNode`, `com.oracle.truffle.polyglot.JDKSupport` |
| `org.graalvm.truffle:truffle-runtime` | `thc-truffle-runtime-25.3.4.1-return1-budget1-aot1.jar` | `OptimizedCallTarget`, `BaseOSRRootNode`, `OptimizedOSRLoopNode`, `BytecodeOSRMetadata`, all in `com.oracle.truffle.runtime` |
| `org.graalvm.llvm:llvm-language` | `thc-llvm-language-25.3.4.1-globals1.jar`; Windows adds `-windows1` before `.jar` | `com.oracle.truffle.llvm.runtime.global.LLVMGlobalContainer`, `com.oracle.truffle.llvm.initialization.InitializeSymbolsNode`; Windows also replaces `com.oracle.truffle.llvm.parser.coff.WindowsLibraryLocator` |

The owning build files are [materializable-api.gradle](../src/gradle/materializable-api.gradle),
[protocol-runtime.gradle](../src/gradle/protocol-runtime.gradle) and
[windows-sulong.gradle](../src/gradle/windows-sulong.gradle). They pin source and
binary hashes, preserve attribution and retain upstream dependency metadata.
Their verification tasks check artifact selection and byte identity of unrelated
entries. Preserving those bytes does not imply unchanged behavior of callers of
the replaced classes.

## Behavior and scope of each patch

| Patch | Why THC needs it | Effect on other users of the runtime |
| --- | --- | --- |
| [materializable-root.patch](../tools/truffle-protocol/materializable-root.patch) | Declare possible frame capture before the first compiled call, including async and delimited continuations. | Opt-in `RootNode.requiresMaterializableFrame()` marks the frame descriptor before target publication. Default `false` preserves observed materialization. Other roots sharing a marked descriptor inherit its conservative materialization state; this can reduce virtualization and selects the parent-frame OSR path. |
| [mount-carrier-lookup.patch](../tools/truffle-protocol/mount-carrier-lookup.patch) | Virtual-thread mount hooks must not load classes or suspend through MethodHandle customization when finding the carrier. | **Applies to every language using classpath-isolated Truffle.** The existing generated module supplies an initialized direct carrier lookup through `Supplier<Thread>`. Its existing qualified exports are installed before setup instantiates the supplier; callbacks use direct interface/static calls. Named-module direct access and other module/thread-local operations are unchanged. |
| [declared-return-api.patch](../tools/truffle-protocol/declared-return-api.patch) | Declare that a root may return either its normal result or a saved continuation. | Adds `requiresUnprofiledReturn()`, default `false`; generated continuation roots forward their source root's declaration. Other languages can opt in to the same contract. |
| [declared-return-runtime.patch](../tools/truffle-protocol/declared-return-runtime.patch) | Avoid learning a continuation's return class or first AOT exception by executing guest code. | Opted-in roots start with a polymorphic return profile. **Separately, every successful explicit AOT preparation initializes a generic exception profile, regardless of language or return declaration.** Such roots give up first-exception speculation; normal non-AOT exception profiling is unchanged. |
| [graph-budget-api.patch](../tools/truffle-protocol/graph-budget-api.patch) | Let a language change a real compilation boundary after an actual graph-size bailout. | Adds generation and recovery hooks, defaulting to no recovery. The compiler-thread callback may acknowledge only a newer structural boundary; it cannot execute guest code, access a current context or submit/wait for compilation. |
| [graph-budget-runtime.patch](../tools/truffle-protocol/graph-budget-runtime.patch) | Retry structurally reduced graphs and allow compiler-thread node replacement without waiting under an OSR owner's AST lock. | Recovery requires an opted-in newer generation; other failures retain normal handling. **OSR submission, publication, reservation and completion use the changed scheduling path for all languages**, even without recovery. Loop and bytecode OSR submit under the lock and finish outside it. Explicit foreground recovery waits/retries after the failed task completes; background recovery leaves the root eligible for a later request. Also exposes the read-only `BaseOSRRootNode.getSourceRootNode()` accessor. |
| [declared-mutability.patch](../tools/sulong-globals/declared-mutability.patch) | A compiled C update to a declared mutable global should not invalidate its caller by discovering that mutability. | **Applies to any LLVM module loaded through this Sulong**, including other languages' native dependencies. Managed non-TLS globals declared mutable start in fallback storage instead of speculating on a constant for their first writes. Readonly globals and the default/TLS constructor retain upstream speculation. Mutable-global optimization and deoptimization behavior can change for unrelated LLVM guests. |
| [optional-cwd.patch](../tools/sulong-windows/optional-cwd.patch) | Permit Windows DLL lookup to continue when guest file IO denies the optional working-directory search. | **Applies to all Windows Sulong dependency lookups.** A `SecurityException` during that optional search skips it and continues existing global/native lookup. Existing loading permissions still apply; the patch grants no file or native access. |

The root protocols are described in more detail in
[runtime protocol support](../tools/truffle-protocol/README.md). Sulong's
[global mutability](../tools/sulong-globals/README.md) and
[Windows lookup](../tools/sulong-windows/README.md) notes cover their native
boundaries. Neither AOT preparation here nor these JVM checks establish
Native Image compatibility.

## Embedding and selecting a runtime

Use one coherent set of pinned API, runtime and Sulong artifacts on the host's
effective class/module path. Adding a second stock copy alongside a patched JAR
is not an isolation mechanism: their class and module names overlap. The
repository's Gradle dependency exclusions do not automatically configure an
external embedding application's dependency graph.

The build option `-Pthc.stockTruffle=true` selects all three upstream artifacts.
It is a build/distribution choice, not a per-context switch. Stock mode keeps
ordinary observed frame and return profiling, has no graph-budget recovery
hooks, and retains upstream AOT exception, mutable-global and Windows lookup
behavior, including the upstream isolated carrier MethodHandle lookup. THC checks that depend on those changed first-compilation or recovery
contracts are not equivalent in stock mode. Adding `-Dthc.stockTruffle=true` to
an already packaged JVM does not change its JARs.

When sharing a host with another language, account for the AOT, OSR and Sulong
changes above in that host's own validation. THC's focused controls exercise the
patched mechanisms; they do not certify every other language's workload or
mixed-language performance. Separate contexts preserve their guest state, but
do not undo shared runtime changes. Use a separate process when applications
must select different runtime implementations.

## Verification and maintenance

Packaging produces the patched JARs; testing validates their selection and
behavior. The focused artifact checks are:

```sh
./gradlew verifyMaterializableApiSelection verifyProtocolRuntimeSelection verifyWindowsSulongSelection
```

Existing controls include `testMaterializableApi`, `testReturnPolicy`,
`testReturnContinuations`, `testOsrSourceOwnership`, `testGraphBudgetRecovery`
and `testOsrGraphBudgetRecovery`. The Sulong notes link their focused runtime
tests. Keep this inventory and the embedding notice current when adding,
removing or upgrading patches, including changes that affect non-THC callers.
