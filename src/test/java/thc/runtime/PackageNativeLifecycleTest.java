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
import thc.ManagedExportSignature;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual LLVM lifecycle metadata, including the original simdutf iostream shape. */
public class PackageNativeLifecycleTest {
    @TempDir public Path directory;
    private static final class Entry extends RootNode {
        @Child private PackageScalarAccess access;
        private final boolean narrow;
        Entry(Language language, PackageScalarCall operation) { super(language); access = new PackageScalarAccess(operation); narrow = operation.getResult().equals("Int32Rep"); }
        @Override public Object execute(VirtualFrame frame) {
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
    @ParameterizedTest @CsvSource({"ast,false", "bytecode,false", "ast,true", "bytecode,true"})
    public void declaredStaticExportIsCallableFromOriginalCConstructorAndRetainedPointer(String backend, boolean reentrant) throws Exception {
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
            original.getBytes(), List.of(original.getAbi().getFirst(), during), original.getFormat());
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
                                var function = owner.getPackageCbits().resolve(link, during).getReceiver();
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
                assertEquals(49, invoke(link), hosting);
                assertEquals(49, invoke(link), "constructor runs only once");
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
    /** Optional replay of an already acquired real native companion, not reacquisition. */
    @Test public void installedCompanionLoadsAndCallsOrdinaryCDespiteUnusedRuntimeReferences() throws Exception {
        var path = System.getenv("THC_TEST_NATIVE_COMPANION"); assumeTrue(path != null);
        // Original PrelIOUtils.c declares const char *localeEncoding(void).
        var link = companion(Files.readAllBytes(Path.of(path)),
            "extern const char *localeEncoding(void); long entry(void) { const char *s = localeEncoding(); return s && *s; }");
        try (var context = Context.newBuilder("thc").allowNativeAccess(true).build()) {
            context.initialize("thc"); context.enter();
            try { assertEquals(1L, invoke(link)); } finally { context.leave(); }
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
