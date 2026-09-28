// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import thc.Main.withContextProfile

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout

class WcwidthTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/wcwidth")

    // Thread-local native locale: do not mutate the JVM's process-wide locale.
    // The guest's synchronous libc call executes on this same carrier thread.
    private fun <T> withLocale(name: String, action: () -> T): T = Arena.ofConfined().use { arena ->
        val linker = Linker.nativeLinker()
        fun function(symbol: String, descriptor: FunctionDescriptor) =
            linker.downcallHandle(linker.defaultLookup().find(symbol).orElseThrow(), descriptor)
        val create = function("newlocale", FunctionDescriptor.of(ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS))
        val use = function("uselocale", FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS))
        val free = function("freelocale", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS))
        val selected = create.invokeWithArguments(1, arena.allocateFrom(name), MemorySegment.NULL) as MemorySegment
        assertNotEquals(0L, selected.address(), "Linux LC_CTYPE locale $name must exist")
        val previous = use.invokeWithArguments(selected) as MemorySegment
        assertNotEquals(0L, previous.address())
        try { action() } finally {
            use.invokeWithArguments(previous)
            free.invokeWithArguments(selected)
        }
    }

    @Test fun originalTastyDeclarationAndFallbackMatchNativeLocalesInBothCompiledBackends() {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf(
            "test/fixtures/run-wcwidth/src/Width.hs", "test/fixtures/run-wcwidth/app/Main.hs",
            "test/haskell-fixtures/WcwidthFixtures.hs", "src/THC/Driver/PackageNative.hs",
            "src/THC/Driver/NativeLibrarySources.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf(
            "build/wcwidth/Width.json", "build/wcwidth/C.tsv", "build/wcwidth/C.UTF-8.tsv",
            "build/wcwidth/rawWidth.json", "build/wcwidth/displayWidth.json", "build/wcwidth/ConsoleReporter.hs",
            "build/wcwidth/TASTY-LICENSE"), "build/wcwidth/")
        for (entry in listOf("rawWidth", "displayWidth"))
            assertEquals(true, (Json.parse(File(directory, "$entry.json").readText()) as Map<*, *>)["accepted"])
        val original = Json.parse(File(directory, "Width.json").readText()) as Map<String, Any?>
        val proof = original["packageNativeLink"] as Map<*, *>
        assertEquals("llvm-embedded-elf", proof["format"])
        val libraries = (proof["buildInputs"] as Map<*, *>)["nativeLibraries"] as List<Map<*, *>>
        assertEquals(listOf("native-libc-wcwidth-v1"), libraries.map { it["provider"] })
        val merged = CoreModules.merge(listOf(original))
        val link = (merged["packageScalarLinks"] as List<PackageScalarLink>).single()
        assertTrue(link.abi.isNotEmpty())
        assertTrue(link.abi.all { it.safety == "safe" && it.convention == "capi" &&
            it.arguments == listOf("Int32Rep") && it.result == "Int32Rep" })
        val observations = listOf("C", "C.UTF-8").associateWith { locale ->
            File(directory, "$locale.tsv").readLines().map { line -> line.split('\t').map(String::toLong) }
        }
        assertTrue(observations.values.all { it.size == 12 })
        assertNotEquals(observations.getValue("C"), observations.getValue("C.UTF-8"), "oracle exercises locale dependence")
        for ((locale, rows) in observations) {
            for ((_, raw, fallback) in rows) assertEquals(if (raw == -1L) 1L else raw, fallback)
            for (backend in listOf("ast", "bytecode")) withLocale(locale) {
                Context.newBuilder("thc").allowNativeAccess(true).let { withContextProfile(it, ContextProfile.SYNCHRONOUS_TEST) }
                    .build().use { context ->
                        context.initialize("thc"); context.enter()
                        try {
                            val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                            val owner = Language.currentState()
                            owner.packageCbits.link(link)
                            owner.threads.enterCurrent()
                            try {
                                for ((index, name) in listOf("rawWidth", "displayWidth").withIndex()) {
                                    val entry = "wcwidth-ffi-0.1.0.0-inplace:Width.$name"
                                    val source = CoreModules.reachable(merged, entry) + ("instrument" to true)
                                    val program: ExecutableProgram = if (backend == "ast") Program(language, source, true)
                                        else BytecodeProgram(language, source, true)
                                    val target = program.entryTarget(entry)
                                    fun checkRows(compiled: Boolean) {
                                        for (row in rows) {
                                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                            assertEquals(row[index + 1], Calls.target(target, arrayOf(0L, row[0])), "$locale/$backend/$name/${row[0]}")
                                            if (compiled) {
                                                assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                                                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                                            }
                                            val handoff = language.handoffState.get()
                                            assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                                            assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                                        }
                                    }
                                    checkRows(false)
                                    target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                                    checkRows(true)
                                }
                            } finally { owner.threads.leaveCurrent() }
                        } finally { context.leave() }
                    }
            }
        }
    }
}
