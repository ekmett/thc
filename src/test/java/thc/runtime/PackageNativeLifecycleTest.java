// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime;

import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.Map;
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import thc.PackageNativeComponent;
import thc.ManagedExportSignature;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual LLVM lifecycle metadata, including the original simdutf iostream shape. */
public class PackageNativeLifecycleTest {
    @TempDir public Path directory;
    private static final class Entry extends RootNode {
        @Child private PackageScalarAccess access;
        private final boolean narrow;
        long compiledEntries;
        Entry(Language language, PackageScalarCall operation) { super(language); access = new PackageScalarAccess(operation); narrow = operation.getResult().equals("Int32Rep"); }
        @Override public Object execute(VirtualFrame frame) {
            if (com.oracle.truffle.api.CompilerDirectives.inCompiledCode()) compiledEntries++;
            return narrow
                ? (long) access.executeInt(new Object[0], thc.runtime.Unit.INSTANCE)
                : access.executeLong(new Object[0], thc.runtime.Unit.INSTANCE);
        }
    }
    private String command(List<String> arguments) throws Exception {
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output;
    }
    private PackageScalarLink companion(byte[] nativeBytes, String body) throws Exception {
        return companion(nativeBytes, body, new PackageScalarSignature("entry", "entry", List.of(), "IntRep"));
    }
    private PackageScalarLink companion(byte[] nativeBytes, String body, PackageScalarSignature signature) throws Exception {
        var source = directory.resolve("companion.c"); var output = directory.resolve("companion.bc");
        Files.writeString(source, body);
        command(List.of(System.getenv().getOrDefault("THC_CLANG", "clang"),
            "--target=x86_64-unknown-linux-gnu", "-O1", "-emit-llvm", "-c", source.toString(), "-o", output.toString()));
        var bytes = Files.readAllBytes(output);
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        return new PackageScalarLink("native-companion", "x86_64-unknown-linux-gnu", hash, hash,
            bytes, List.of(signature), "llvm-bitcode", Set.of(), nativeBytes);
    }
    @ParameterizedTest @ValueSource(strings = {"platform", "loom"})
    public void originalCQueriesOnlyItsContextsBoundThreadSupport(String hosting) throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var link = companion(new byte[0], "extern long rtsSupportsBoundThreads(void); long entry(void) { return rtsSupportsBoundThreads(); }");
        var interop = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
        Object escaped;
        try (var first = Context.newBuilder("thc").allowNativeAccess(true).allowCreateThread(true)
                .allowExperimentalOptions(true).option("thc.ThreadHosting", hosting)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build();
             var second = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            first.initialize("thc"); second.initialize("thc"); first.enter();
            try {
                var owner = Language.currentState(); owner.getPackageCbits().link(link);
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var entry = new Entry(language, new PackageScalarCall(link, link.getAbi().getFirst()));
                var target = entry.getCallTarget();
                target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                assertEquals(0L, target.call(), "THC cannot promise GHC bound-thread semantics");
                // Ordinary static calls fill their existing function/result caches
                // on first use. Prove cold compiled ENTRY, not retained compilation.
                assertEquals(1L, entry.compiledEntries);
                assertSame(target, entry.getCallTarget());
                // Exercise the service guard itself; a raw Sulong function is
                // not an API for entering a different context's sharing layer.
                escaped = new NativeCallbacks.BoundThreadQuery(owner.getNativeCallbacks());
                assertEquals(0L, interop.execute(escaped));
                assertThrows(com.oracle.truffle.api.interop.ArityException.class, () -> interop.execute(escaped, 1L));
            } finally { first.leave(); }
            second.enter();
            try { assertThrows(RuntimeFault.class, () -> interop.execute(escaped), "service cannot use another context"); }
            finally { second.leave(); }
            first.enter();
            try {
                Language.currentState().getNativeCallbacks().close();
                assertThrows(RuntimeFault.class, () -> interop.execute(escaped), "service cannot outlive its registry");
            } finally { first.leave(); }
        }
    }
    @Test public void originalCReleasesOnlyItsContextsLiveStablePointer() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var signature = new PackageScalarSignature("entry", "entry", List.of("AddrRep"), "void");
        var link = companion(new byte[0], "extern void hs_free_stable_ptr(void*); void entry(void *p) { hs_free_stable_ptr(p); }", signature);
        var interop = com.oracle.truffle.api.interop.InteropLibrary.getUncached();
        try (var first = Context.newBuilder("thc").allowNativeAccess(true).build();
             var second = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            first.initialize("thc"); second.initialize("thc");
            ManagedAddress stable; long token;
            first.enter();
            try {
                var state = Language.currentState();
                stable = state.getStablePointers().make(new Object());
                token = state.getStablePointers().nativeToken(stable);
            } finally { first.leave(); }
            second.enter();
            try {
                var state = Language.currentState(); state.getPackageCbits().link(link);
                var target = state.getPackageCbits().resolve(link, signature).getReceiver();
                assertThrows(Exception.class, () -> interop.execute(target, new PackageNativePointer(token, null)),
                    "a foreign context cannot release the token's owner");
            } finally { second.leave(); }
            first.enter();
            try {
                var state = Language.currentState(); state.getStablePointers().validate(stable);
                state.getPackageCbits().link(link);
                var target = state.getPackageCbits().resolve(link, signature).getReceiver();
                interop.execute(target, new PackageNativePointer(token, null));
                assertThrows(RuntimeFault.class, () -> state.getStablePointers().dereference(stable));
                assertThrows(Exception.class, () -> interop.execute(target, new PackageNativePointer(token, null)), "double release");
                assertThrows(Exception.class, () -> interop.execute(target, new PackageNativePointer(1, null)), "fabricated token");
                assertThrows(Exception.class, () -> interop.execute(target, new PackageNativePointer(0, null)), "null is not a managed token");
            } finally { first.leave(); }
        }
    }
    @Timeout(15)
    @ParameterizedTest @CsvSource({"ast,false,false", "bytecode,false,false", "ast,true,false", "bytecode,true,false",
        "ast,false,true", "bytecode,false,true", "ast,true,true", "bytecode,true,true"})
    public void declaredStaticExportIsCallableFromOriginalCConstructorAndRetainedPointer(String backend, boolean reentrant, boolean dependency) throws Exception {
        staticExport(backend, reentrant, dependency, false);
    }
    @ParameterizedTest @ValueSource(strings = {"ast", "bytecode"}) @Timeout(5)
    public void ancestorConstructorReentryNeverWaitsForItsOwnPendingComponent(String backend) throws Exception {
        staticExport(backend, true, true, true);
    }
    private void staticExport(String backend, boolean reentrant, boolean dependency, boolean ancestor) throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var original = companion(new byte[0], """
            extern int declared_identity(int);
            static int (*retained)(int);
            static int initialized;
            __attribute__((constructor)) static void initialize(void) {
                retained = declared_identity; initialized = retained(7);
            }
            long entry(void) { return initialized + retained(42); }
            long during(void) { return 5; }
            """, new PackageScalarSignature("entry", "entry", List.of(), "IntRep", "ccall", "safe"));
        var during = new PackageScalarSignature("during", "during", List.of(), "IntRep", "ccall", "safe");
        var link = new PackageScalarLink(original.getUnit(), original.getTarget(), original.getComponentSha256(), original.getBitcodeSha256(),
            original.getBytes(), List.of(original.getAbi().getFirst(), during), original.getFormat(), Set.of(), new byte[0], Set.of(),
            Set.of("entry", "during"), List.of());
        var consumerBase = !dependency ? link : component("consumer", "extern long entry(void); long consumer_entry(void) { return entry(); } long consumer_during(void) { return 5; }",
            "consumer_entry", Set.of("consumer_entry"), List.of(link.getComponent()));
        var consumerDuring = new PackageScalarSignature("consumer_during", "consumer_during", List.of(), "IntRep", "ccall", "safe");
        var consumer = !ancestor ? consumerBase : new PackageScalarLink(consumerBase.getUnit(), consumerBase.getTarget(),
            consumerBase.getComponentSha256(), consumerBase.getBitcodeSha256(), consumerBase.getBytes(),
            List.of(consumerBase.getAbi().getFirst(), consumerDuring), consumerBase.getFormat(), Set.of(), new byte[0], Set.of(),
            Set.of("consumer_entry", "consumer_during"), List.of(link.getComponent()));
        var callbackLink = ancestor ? consumer : link;
        var callbackSignature = ancestor ? consumerDuring : during;
        for (String hosting : List.of("platform", "loom")) try (var context = Context.newBuilder("thc")
                .allowNativeAccess(true).allowCreateThread(true).allowExperimentalOptions(true)
                .option("thc.ThreadHosting", hosting).build()) {
            context.initialize("thc"); context.enter();
            try {
                var owner = Language.currentState();
                var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                var data = Map.of("kind", "data", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", false);
                var closure = Map.of("kind", "closure", "primReps", List.of("BoxedRep (Just Lifted)"), "evaluated", true);
                var module = Map.<String,Object>of("instrument", true, "bindings", List.of(Map.of(
                    "id", "model:Static.identity", "name", "identity", "lifted", true, "arity", 1, "rep", closure,
                    "expr", List.of("lam", List.of(Map.of("id", "x", "lifted", true, "coercion", false, "rep", data)),
                        List.of("var", "x", Map.of("rep", data)), Map.of("rep", closure, "resultRep", data)))),
                    "constructors", List.of(Map.of("id", "ghc-internal:GHC.Internal.Int.I32#", "name", "I32#", "kind", "boxed",
                        "arity", 1, "fieldReps", List.of(List.of("Int32Rep")), "strictFields", List.of(false), "fieldLifted", List.of(false))));
                ExecutableProgram delegate = backend.equals("ast") ? new Program(language, module, false, false) : new BytecodeProgram(language, module, false);
                ExecutableProgram program = !reentrant ? delegate : new ExecutableProgram() {
                    @Override public boolean getAsynchronousExceptions() { return delegate.getAsynchronousExceptions(); }
                    @Override public com.oracle.truffle.api.RootCallTarget hostEntryTarget(int arity) {
                        var target = delegate.hostEntryTarget(arity);
                        return new RootNode(language) {
                            @Override public Object execute(VirtualFrame frame) {
                                var function = owner.getPackageCbits().resolve(callbackLink, callbackSignature).getReceiver();
                                try { assertEquals(5L, com.oracle.truffle.api.interop.InteropLibrary.getUncached().asLong(
                                    com.oracle.truffle.api.interop.InteropLibrary.getUncached().execute(function))); }
                                catch (com.oracle.truffle.api.interop.InteropException failure) { throw new AssertionError(failure); }
                                return Calls.target(target, frame.getArguments());
                            }
                        }.getCallTarget();
                    }
                    @Override public Object entryValue(String name) { return delegate.entryValue(name); }
                    @Override public com.oracle.truffle.api.RootCallTarget entryTarget(String name) { return delegate.entryTarget(name); }
                    @Override public DataLayout constructorLayout(String id) { return delegate.constructorLayout(id); }
                    @Override public Map<String,Object> diagnostics() { return delegate.diagnostics(); }
                };
                var type = Map.<String,Object>of("kind", "tycon", "name", Map.of("unit", "ghc-internal", "module", "GHC.Internal.Int",
                    "occurrence", "Int32", "namespace", "type"), "arguments", List.of());
                var declaration = new ManagedExportSignature("model", "Static", "declared_identity", "model:Static.identity", List.of(type), type, false, 64);
                owner.getNativeCallbacks().registerStaticExports(program, language, List.of(declaration));
                owner.getPackageCbits().declare(link);
                if (ancestor) {
                    var failure = assertThrows(RuntimeFault.class, () -> invoke(consumer));
                    assertTrue(failure.getMessage().contains("consumer") && failure.getMessage().contains("awaiting native dependencies"), failure.toString());
                    assertSame(failure, assertThrows(RuntimeFault.class, () -> invoke(consumer)), "failure is retained, not retried");
                    owner.getNativeCallbacks().unregisterStaticExports(program);
                    assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                    assertEquals(0, language.getHandoffState().get().getResults().getDepth());
                    continue;
                }
                assertEquals(49, invoke(consumer), hosting);
                assertEquals(49, invoke(consumer), "constructor runs only once");
                owner.getNativeCallbacks().unregisterStaticExports(program);
                var target = owner.getPackageCbits().resolve(link, link.getAbi().getFirst()).getReceiver();
                assertThrows(Exception.class, () -> com.oracle.truffle.api.interop.InteropLibrary.getUncached().execute(target),
                    "retained pointer remains allocated but cannot enter an unregistered program");
                assertEquals(0, language.getHandoffState().get().getArguments().getDepth());
                assertEquals(0, language.getHandoffState().get().getResults().getDepth());
            } finally { context.leave(); }
        }
    }
    private long invoke(PackageScalarLink link) {
        var registry = Language.currentState().getPackageCbits(); registry.link(link); registry.link(link);
        var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
        return (long) new Entry(language, new PackageScalarCall(link, link.getAbi().getFirst())).getCallTarget().call();
    }
    private PackageScalarLink component(String unit, String body, String entry, Set<String> exports,
            List<PackageNativeComponent> dependencies) throws Exception {
        var compiled = companion(new byte[0], body, new PackageScalarSignature(entry, entry, List.of(), "IntRep", "ccall", "safe"));
        return new PackageScalarLink(unit, compiled.getTarget(), compiled.getComponentSha256(), compiled.getBitcodeSha256(),
            compiled.getBytes(), compiled.getAbi(), compiled.getFormat(), Set.of(), new byte[0], Set.of(), exports, dependencies);
    }
    @Test public void declaredMixedProvidersShareOneInitializedNativeStateAcrossConsumers() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var provider = component("provider", """
            static long state;
            __attribute__((constructor)) static void initialize(void) { state = 40; }
            long provider_next(void) { return ++state; }
            """, "provider_next", Set.of("provider_next"), List.of());
        var first = component("first", """
            extern long provider_next(void);
            static long initial;
            __attribute__((constructor)) static void initialize(void) { initial = provider_next(); }
            long first_entry(void) { return 100 * initial + provider_next(); }
            """, "first_entry", Set.of("first_entry"), List.of(provider.getComponent()));
        var second = component("second", """
            extern long provider_next(void);
            static long initial;
            __attribute__((constructor)) static void initialize(void) { initial = provider_next(); }
            long second_entry(void) { return 100 * initial + provider_next(); }
            """, "second_entry", Set.of("second_entry"), List.of(provider.getComponent()));
        for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                assertEquals(4142L, invoke(first), "provider constructor precedes the first consumer");
                assertEquals(4344L, invoke(second), "second consumer sees the same provider state");
                assertEquals(4145L, invoke(first), "neither constructor repeats");
                assertEquals(46L, invoke(provider), "later typed admission uses the already loaded provider");
            } finally { context.leave(); }
        }
    }
    @Test public void declaredDataExportsRetainTheirProvidersStorageAcrossConsumers() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var provider = component("provider", """
            long provider_value;
            const char provider_ident[] = "real";
            __attribute__((constructor)) static void initialize(void) { provider_value = 40; }
            long provider_next(void) { return ++provider_value; }
            """, "provider_next", Set.of("provider_value", "provider_ident", "provider_next"), List.of());
        var consumer = component("consumer", """
            extern long provider_value;
            extern const char provider_ident[];
            long consumer_entry(void) { return 100 * provider_value + provider_ident[0]; }
            """, "consumer_entry", Set.of("consumer_entry"), List.of(provider.getComponent()));
        for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                assertEquals(4114L, invoke(consumer));
                assertEquals(41L, invoke(provider));
                assertEquals(4214L, invoke(consumer), "consumer and typed provider share the same mutable global");
            } finally { context.leave(); }
        }
    }
    @Test public void declaredExportsCannotBeBorrowedFromAnotherLoadedProvider() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var provider = component("provider", "long missing_export = 7; long provider_entry(void) { return missing_export; }",
            "provider_entry", Set.of("provider_entry"), List.of());
        var claimant = component("claimant", "extern long missing_export; long claimant_entry(void) { return missing_export; }",
            "claimant_entry", Set.of("claimant_entry", "missing_export"), List.of(provider.getComponent()));
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                assertEquals(7L, invoke(provider));
                var failure = assertThrows(RuntimeFault.class, () -> invoke(claimant));
                assertTrue(failure.getMessage().contains("Missing package C provider export: missing_export"), failure.toString());
                assertSame(failure, assertThrows(RuntimeFault.class, () -> invoke(claimant)), "failed publication is not retried");
                assertEquals(7L, invoke(provider));
            } finally { context.leave(); }
        }
    }
    @Test public void standardLoweringExecutesRealCRelativeStringTables() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var original = companion(new byte[0], """
            static const char *const names[] = {"zero", "one", "two", "three", "four", "five"};
            static unsigned index;
            long entry(void) { return names[index++ % 6][0]; }
            #ifdef NATIVE_ORACLE
            #include <stdio.h>
            int main(void) { for (int i = 0; i < 7; ++i) printf("%ld\\n", entry()); }
            #endif
            """);
        var compiler = System.getenv().getOrDefault("THC_CLANG", "clang");
        var nativeProgram = directory.resolve("native-relative-table");
        command(List.of(compiler, "-O1", "-DNATIVE_ORACLE", directory.resolve("companion.c").toString(), "-o", nativeProgram.toString()));
        assertEquals("122\n111\n116\n116\n102\n102\n122\n", command(List.of(nativeProgram.toString())));
        var opt = System.getenv().getOrDefault("THC_LLVM_OPT", "opt");
        var input = directory.resolve("companion.bc");
        assertTrue(command(List.of(opt, "-S", "-passes=verify", input.toString(), "-o", "-")).contains("call ptr @llvm.load.relative"),
            "the genuine compiler input must exercise a relative table");
        // Characterize the pinned Sulong gap independently of the standard LLVM
        // expansion. The producer's native test checks that its final pipeline
        // applies this pass; this control checks actual guest execution.
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var failure = assertThrows(com.oracle.truffle.api.exception.AbstractTruffleException.class, () -> invoke(original));
                assertTrue(failure.getMessage().contains("missing LLVM builtin: llvm.load.relative"), failure.toString());
            } finally { context.leave(); }
        }
        var output = directory.resolve("relative-lowered.bc");
        if (List.of(command(List.of(opt, "--print-passes")).split("\\s+")).contains("pre-isel-intrinsic-lowering")) {
            command(List.of(opt, "-passes=pre-isel-intrinsic-lowering,globaldce", input.toString(), "-o", output.toString()));
        } else {
            // LLVM 18 exposes the same production lowering through its legacy pass manager.
            var lowered = directory.resolve("relative-pre-isel.bc");
            command(List.of(opt, "--mtriple=" + original.getTarget(), "-pre-isel-intrinsic-lowering", input.toString(), "-o", lowered.toString()));
            command(List.of(opt, "-passes=globaldce", lowered.toString(), "-o", output.toString()));
        }
        var bytes = Files.readAllBytes(output);
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var lowered = new PackageScalarLink("relative-lowered", original.getTarget(), hash, hash, bytes,
            original.getAbi(), "llvm-bitcode", Set.of(), new byte[0]);
        for (int i = 0; i < 2; i++) try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                for (long expected : new long[]{122, 111, 116, 116, 102, 102, 122}) assertEquals(expected, invoke(lowered));
            } finally { context.leave(); }
        }
    }
    @Test public void failedProviderInitializationIsNotRetriedByAnotherConsumer() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var provider = component("provider", """
            extern long missing_service(void);
            __attribute__((constructor)) static void initialize(void) { missing_service(); }
            long provider_next(void) { return 1; }
            """, "provider_next", Set.of("provider_next"), List.of());
        var first = component("first", "extern long provider_next(void); long first_entry(void) { return provider_next(); }",
            "first_entry", Set.of("first_entry"), List.of(provider.getComponent()));
        var second = component("second", "extern long provider_next(void); long second_entry(void) { return provider_next(); }",
            "second_entry", Set.of("second_entry"), List.of(provider.getComponent()));
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var failure = assertThrows(Exception.class, () -> invoke(first));
                assertTrue(failure.getMessage().contains("missing_service"), failure.toString());
                assertSame(failure, assertThrows(Exception.class, () -> invoke(first)));
                assertSame(failure, assertThrows(Exception.class, () -> invoke(second)));
                assertSame(failure, assertThrows(Exception.class, () -> invoke(provider)));
            } finally { context.leave(); }
        }
    }
    @Test public void nativeCompanionsDeferUnusedReferencesWithoutSharingProvidersBetweenContexts() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var source = directory.resolve("dependency.c"); var output = directory.resolve("dependency.so");
        Files.writeString(source, """
            extern long missing_native_service(void);
            long unused_wrapper(void) { return missing_native_service(); }
            static long state;
            __attribute__((constructor)) static void initialize(void) { state = 30; }
            long next_value(void) { return ++state; }
            """);
        command(List.of(System.getenv().getOrDefault("THC_CLANG", "clang"),
            "-O1", "-shared", "-fPIC", source.toString(), "-o", output.toString()));
        var bytes = Files.readAllBytes(output);
        var link = companion(bytes, "extern long next_value(void); static long calls; long entry(void) { return next_value() + 100 * ++calls; }");
        try (var first = Context.newBuilder("thc").allowNativeAccess(true).build();
             var second = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            first.initialize("thc"); second.initialize("thc");
            first.enter(); try { assertEquals(131L, invoke(link)); } finally { first.leave(); }
            second.enter(); try { assertEquals(131L, invoke(link)); } finally { second.leave(); }
            first.enter(); try { assertEquals(232L, invoke(link)); } finally { first.leave(); }
            second.enter(); try { assertEquals(232L, invoke(link)); } finally { second.leave(); }
        }
        try (var forbidden = Context.newBuilder("thc").build()) {
            forbidden.initialize("thc"); forbidden.enter();
            try { assertThrows(RuntimeFault.class, () -> Language.currentState().getPackageCbits().link(link)); }
            finally { forbidden.leave(); }
        }
        var missing = companion(bytes, "extern long absent_companion_target(void); long entry(void) { return absent_companion_target(); }");
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var registry = Language.currentState().getPackageCbits(); registry.link(missing);
                var function = registry.resolve(missing, missing.getAbi().getFirst()).getReceiver();
                var failure = assertThrows(Exception.class, () -> com.oracle.truffle.api.interop.InteropLibrary.getUncached().execute(function));
                assertTrue(failure.getMessage().contains("absent_companion_target"), failure.toString());
            } finally { context.leave(); }
        }
    }
    @Test public void bundledNativeProvidersRetainTransitiveConstructorsAfterOriginalsAreDeleted() throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var compiler = System.getenv().getOrDefault("THC_CLANG", "clang");
        var suffix = directory.getFileName().toString().replaceAll("[^A-Za-z0-9_]", "_");
        var leafName = "libthc_leaf_" + suffix + ".so";
        var rootName = "libthc_root_" + suffix + ".so";
        var trace = directory.resolve("bundle-trace.txt");
        var traceLiteral = trace.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        var leafSource = directory.resolve("leaf.c"); var rootSource = directory.resolve("root.c");
        var leafFile = directory.resolve(leafName); var rootFile = directory.resolve(rootName);
        Files.writeString(leafSource, """
            #include <stdio.h>
            #include <stdlib.h>
            static long state;
            __attribute__((constructor)) static void initialize(void) {
                state = 40; FILE *f = fopen("%s", "a"); if (!f) abort(); fputs("leaf\\n", f); fclose(f);
            }
            long bundled_leaf_next(void) { return ++state; }
            """.formatted(traceLiteral));
        Files.writeString(rootSource, """
            #include <stdio.h>
            #include <stdlib.h>
            extern long bundled_leaf_next(void);
            static long initial;
            __attribute__((constructor)) static void initialize(void) {
                initial = bundled_leaf_next(); FILE *f = fopen("%s", "a"); if (!f) abort(); fputs("root\\n", f); fclose(f);
            }
            long bundled_root_next(void) { return 100 * initial + bundled_leaf_next(); }
            """.formatted(traceLiteral));
        command(List.of(compiler, "-shared", "-fPIC", leafSource.toString(), "-Wl,-soname," + leafName, "-o", leafFile.toString()));
        command(List.of(compiler, "-shared", "-fPIC", rootSource.toString(), "-L" + directory, "-l:" + leafName,
            "-Wl,-soname," + rootName, "-Wl,-rpath,$ORIGIN", "-o", rootFile.toString()));
        var oracleSource = directory.resolve("bundle-oracle.c"); var oracle = directory.resolve("bundle-oracle");
        Files.writeString(oracleSource, "#include <stdio.h>\nextern long bundled_root_next(void);\nint main(void) { printf(\"%ld\\n\", bundled_root_next() + 1); printf(\"%ld\\n\", bundled_root_next() + 1); }\n");
        command(List.of(compiler, oracleSource.toString(), "-L" + directory, "-l:" + rootName,
            "-Wl,-rpath," + directory, "-o", oracle.toString()));
        var expected = command(List.of(oracle.toString())).lines().map(Long::parseLong).toList();
        assertEquals(List.of(4143L, 4144L), expected);
        assertEquals("leaf\nroot\n", Files.readString(trace)); Files.delete(trace);
        var bundled = new ArrayList<PackageNativeComponent.BundledLibrary>();
        for (var file : List.of(leafFile, rootFile)) {
            var bytes = Files.readAllBytes(file);
            bundled.add(new PackageNativeComponent.BundledLibrary(file.getFileName().toString(),
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)), bytes));
        }
        var links = new ArrayList<PackageScalarLink>();
        for (int i = 0; i < 2; i++) {
            var source = directory.resolve("consumer" + i + ".c"); var output = directory.resolve("consumer" + i + ".so");
            var symbol = "bundled_consumer_" + i;
            Files.writeString(source, "extern long bundled_root_next(void); static long state; __attribute__((constructor)) static void initialize(void) { state = bundled_root_next(); } long " + symbol + "(void) { return ++state; }");
            command(List.of(compiler, "-shared", "-fPIC", source.toString(), "-L" + directory, "-l:" + rootName, "-o", output.toString()));
            var original = companion(Files.readAllBytes(output), "extern long " + symbol + "(void); static long calls; long entry(void) { return " + symbol + "() + 100000 * ++calls; }");
            links.add(new PackageScalarLink("bundled-consumer-" + i, original.getTarget(), original.getComponentSha256(),
                original.getBitcodeSha256(), original.getBytes(), original.getAbi(), original.getFormat(), Set.of(),
                original.getNativeLibrary(), Set.of(), Set.of(), List.of(), Map.of(), bundled));
            Files.delete(output);
        }
        Files.delete(leafFile); Files.delete(rootFile);
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                assertEquals(100000L + expected.get(0), invoke(links.get(0)));
                assertEquals(100000L + expected.get(1), invoke(links.get(1)), "consumers share the retained provider");
                assertEquals(200001L + expected.get(0), invoke(links.get(0)), "companion and LLVM state survive repeated linkage");
                assertEquals("leaf\nroot\n", Files.readString(trace), "provider constructors run once in dependency order");
                var conflicting = new PackageNativeComponent.BundledLibrary(leafName,
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(new byte[]{1})), new byte[]{1});
                var original = companion(links.getFirst().getNativeLibrary(), "long entry(void) { return 0; }");
                var conflict = new PackageScalarLink("conflicting-bundle", original.getTarget(), original.getComponentSha256(),
                    original.getBitcodeSha256(), original.getBytes(), original.getAbi(), original.getFormat(), Set.of(),
                    original.getNativeLibrary(), Set.of(), Set.of(), List.of(), Map.of(), List.of(conflicting));
                assertThrows(RuntimeFault.class, () -> Language.currentState().getPackageCbits().declare(conflict));
            } finally { context.leave(); }
        }
        var missingName = "libthc_missing_" + suffix + ".so"; var missingFile = directory.resolve(missingName);
        command(List.of(compiler, "-shared", "-fPIC", leafSource.toString(), "-Wl,-soname," + missingName, "-o", missingFile.toString()));
        command(List.of(compiler, "-shared", "-fPIC", rootSource.toString(), "-L" + directory, "-l:" + missingName,
            "-Wl,-soname,libthc_unavailable.so", "-o", rootFile.toString()));
        var missingBytes = Files.readAllBytes(rootFile);
        var missing = new PackageNativeComponent.BundledLibrary("libthc_unavailable.so",
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(missingBytes)), missingBytes);
        Files.delete(missingFile); Files.delete(rootFile);
        var failed = new ArrayList<PackageScalarLink>();
        for (int i = 0; i < 2; i++) {
            var original = companion(links.getFirst().getNativeLibrary(), "long entry(void) { return " + i + "; }");
            failed.add(new PackageScalarLink("missing-bundle-" + i, original.getTarget(), original.getComponentSha256(),
                original.getBitcodeSha256(), original.getBytes(), original.getAbi(), original.getFormat(), Set.of(),
                original.getNativeLibrary(), Set.of(), Set.of(), List.of(), Map.of(), List.of(missing)));
        }
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try {
                var failure = assertThrows(Throwable.class, () -> invoke(failed.getFirst()));
                assertTrue(failure.getMessage().contains(missingName), failure.toString());
                assertSame(failure, assertThrows(Throwable.class, () -> invoke(failed.getFirst())));
                assertSame(failure, assertThrows(Throwable.class, () -> invoke(failed.getLast())), "provider failure is shared across consumers");
            } finally { context.leave(); }
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    public void constructorsRunOncePerContextAndNormalCloseRunsDestructors(boolean cxx) throws Exception {
        assumeTrue(System.getProperty("os.name").equals("Linux") && System.getProperty("os.arch").equals("amd64"));
        var source = directory.resolve(cxx ? "lifecycle.cpp" : "lifecycle.c");
        var artifact = directory.resolve(cxx ? "lifecycle.so" : "lifecycle.bc");
        var nativeExecutable = directory.resolve("native"); var trace = directory.resolve("trace.txt");
        var traceLiteral = trace.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        var languagePrefix = cxx ? "extern \"C\"" : "";
        Files.writeString(source, """
            #include <stdio.h>
            #include <stdlib.h>
            %s
            static int state;
            static void record(const char *text) { FILE *file = fopen("%s", "a"); if (!file) abort(); fprintf(file, "%%s\\n", text); fclose(file); }
            __attribute__((constructor(101))) static void first(void) { state = 1; record("first"); }
            __attribute__((constructor(201))) static void second(void) { state = state * 10 + 2; record("second"); }
            __attribute__((destructor(101))) static void last(void) { record("last"); }
            __attribute__((destructor(201))) static void penultimate(void) { record("penultimate"); }
            %s
            %s int lifecycle_value(void) { record("call"); return state++; }
            #ifdef NATIVE_ORACLE
            int main(void) { lifecycle_value(); lifecycle_value(); return 0; }
            #endif
            """.formatted(cxx ? "#include <iostream>" : "", traceLiteral,
                cxx ? "struct witness { witness() { record(\"cxx-first\"); } ~witness() { record(\"cxx-last\"); } }; static witness observed;" : "", languagePrefix).stripTrailing());
        var compiler = System.getenv("THC_CLANG") == null ? "clang" : System.getenv("THC_CLANG");
        var common = List.of(compiler, "--target=x86_64-unknown-linux-gnu", "-O1", source.toString());
        var libraries = cxx ? List.of("-lstdc++") : List.<String>of();
        var nativeCommand = new ArrayList<>(common); nativeCommand.addAll(List.of("-DNATIVE_ORACLE", "-o", nativeExecutable.toString())); nativeCommand.addAll(libraries);
        command(nativeCommand); command(List.of(nativeExecutable.toString()));
        var expected = Files.readString(trace);
        var artifactCommand = new ArrayList<>(common); artifactCommand.addAll(cxx ? List.of("-shared", "-fPIC", "-fembed-bitcode") : List.of("-emit-llvm", "-c"));
        artifactCommand.addAll(List.of("-o", artifact.toString())); artifactCommand.addAll(libraries); command(artifactCommand);
        var bytes = Files.readAllBytes(artifact); var sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var signature = new PackageScalarSignature("lifecycle_value", "lifecycle_value", List.of(), "Int32Rep");
        var link = new PackageScalarLink("native-lifecycle", "x86_64-unknown-linux-gnu", sha, sha, bytes, List.of(signature), cxx ? "llvm-embedded-elf" : "llvm-bitcode");
        for (int i = 0; i < 2; i++) {
            Files.delete(trace);
            try (var context = Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()) {
                context.initialize("thc"); context.enter();
                try {
                    var registry = Language.currentState().getPackageCbits(); registry.link(link); registry.link(link);
                    var language = TruffleLanguage.LanguageReference.create(Language.class).get(null);
                    var target = new Entry(language, new PackageScalarCall(link, signature)).getCallTarget();
                    assertEquals(12L, target.call());
                    target.getClass().getMethod("compile", boolean.class).invoke(target, true);
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                    assertEquals(13L, target.call(), "first installed call preserves initialized globals");
                    assertEquals(true, target.getClass().getMethod("isValidLastTier").invoke(target));
                } finally { context.leave(); }
            }
            assertEquals(expected, Files.readString(trace), "native lifecycle order and context isolation");
        }
    }
}
