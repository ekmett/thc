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
import org.graalvm.polyglot.Context;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import thc.Language;
import thc.PackageScalarLink;
import thc.PackageScalarSignature;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Actual LLVM lifecycle metadata, including the original simdutf iostream shape. */
public class PackageNativeLifecycleTest {
    @TempDir public Path directory;
    private static final class Entry extends RootNode {
        @Child private PackageScalarAccess access;
        Entry(Language language, PackageScalarCall operation) { super(language); access = new PackageScalarAccess(operation); }
        @Override public Object execute(VirtualFrame frame) { return access.executeLong(new Object[0], thc.runtime.Unit.INSTANCE); }
    }
    private String command(List<String> arguments) throws Exception {
        var process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output;
    }
    private PackageScalarLink companion(byte[] nativeBytes, String body) throws Exception {
        var source = directory.resolve("companion.c"); var output = directory.resolve("companion.bc");
        Files.writeString(source, body);
        command(List.of(System.getenv().getOrDefault("THC_CLANG", "clang"),
            "--target=x86_64-unknown-linux-gnu", "-O1", "-emit-llvm", "-c", source.toString(), "-o", output.toString()));
        var bytes = Files.readAllBytes(output);
        var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        var signature = new PackageScalarSignature("entry", "entry", List.of(), "IntRep");
        return new PackageScalarLink("native-companion", "x86_64-unknown-linux-gnu", hash, hash,
            bytes, List.of(signature), "llvm-bitcode", Set.of(), nativeBytes);
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
