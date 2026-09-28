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
import org.graalvm.polyglot.Context;
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
