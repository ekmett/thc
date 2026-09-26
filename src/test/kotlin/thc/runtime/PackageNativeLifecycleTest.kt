// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.VirtualFrame
import com.oracle.truffle.api.nodes.RootNode
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import thc.Language
import thc.PackageScalarLink
import thc.PackageScalarSignature
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Actual LLVM lifecycle metadata, including the original simdutf iostream shape. */
class PackageNativeLifecycleTest {
    @TempDir lateinit var directory: Path

    private class Entry(language: Language, operation: PackageScalarCall) : RootNode(language) {
        @Child private var access = PackageScalarAccess(operation)
        override fun execute(frame: VirtualFrame): Any = access.executeLong(emptyArray(), Unit)
    }

    private fun command(arguments: List<String>): String {
        val process = ProcessBuilder(arguments).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        return output
    }

    @ParameterizedTest @ValueSource(booleans = [false, true])
    fun constructorsRunOncePerContextAndNormalCloseRunsDestructors(cxx: Boolean) {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") == "amd64")
        val source = directory.resolve(if (cxx) "lifecycle.cpp" else "lifecycle.c")
        val artifact = directory.resolve(if (cxx) "lifecycle.so" else "lifecycle.bc")
        val native = directory.resolve("native")
        val trace = directory.resolve("trace.txt")
        val traceLiteral = trace.toString().replace("\\", "\\\\").replace("\"", "\\\"")
        val language = if (cxx) "extern \"C\"" else ""
        Files.writeString(source, """
            #include <stdio.h>
            #include <stdlib.h>
            ${if (cxx) "#include <iostream>" else ""}
            static int state;
            static void record(const char *text) { FILE *file = fopen("$traceLiteral", "a"); if (!file) abort(); fprintf(file, "%s\n", text); fclose(file); }
            __attribute__((constructor(101))) static void first(void) { state = 1; record("first"); }
            __attribute__((constructor(201))) static void second(void) { state = state * 10 + 2; record("second"); }
            __attribute__((destructor(101))) static void last(void) { record("last"); }
            __attribute__((destructor(201))) static void penultimate(void) { record("penultimate"); }
            ${if (cxx) "struct witness { witness() { record(\"cxx-first\"); } ~witness() { record(\"cxx-last\"); } }; static witness observed;" else ""}
            $language int lifecycle_value(void) { record("call"); return state++; }
            #ifdef NATIVE_ORACLE
            int main(void) { lifecycle_value(); lifecycle_value(); return 0; }
            #endif
        """.trimIndent())
        val compiler = System.getenv("THC_CLANG") ?: "clang"
        val common = listOf(compiler, "--target=x86_64-unknown-linux-gnu", "-O1", source.toString())
        val libraries = if (cxx) listOf("-lstdc++") else emptyList()
        command(common + listOf("-DNATIVE_ORACLE", "-o", native.toString()) + libraries)
        command(listOf(native.toString()))
        val expected = Files.readString(trace)
        command(common + (if (cxx) listOf("-shared", "-fPIC", "-fembed-bitcode") else listOf("-emit-llvm", "-c")) +
            listOf("-o", artifact.toString()) + libraries)
        val bytes = Files.readAllBytes(artifact)
        val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
        val signature = PackageScalarSignature("lifecycle_value", "lifecycle_value", emptyList(), "Int32Rep")
        val link = PackageScalarLink("native-lifecycle", "x86_64-unknown-linux-gnu", sha, sha, bytes,
            listOf(signature), if (cxx) "llvm-embedded-elf" else "llvm-bitcode")
        repeat(2) {
            Files.delete(trace)
            Context.newBuilder("thc").allowNativeAccess(true).allowExperimentalOptions(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.CompilationFailureAction", "Throw").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val registry = Language.currentState().packageCbits
                    registry.link(link)
                    registry.link(link)
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val target = Entry(language, PackageScalarCall(link, signature)).callTarget
                    assertEquals(12L, target.call())
                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                    assertEquals(13L, target.call(), "first installed call preserves initialized globals")
                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                } finally { context.leave() }
            }
            assertEquals(expected, Files.readString(trace), "native lifecycle order and context isolation")
        }
    }
}
