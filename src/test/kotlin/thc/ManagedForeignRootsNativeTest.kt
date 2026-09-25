// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Engine
import org.graalvm.polyglot.Source
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.runtime.*
import java.io.File
import java.security.MessageDigest

class ManagedForeignRootsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/interface-core")
    private val core = File(directory, "typed-foreign-exports/registration.json")
    private fun verified(file: File): String {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        val digest = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals((manifest["artifactHashes"] as Map<String, String>)[file.relativeTo(root).path], digest)
        return file.readText()
    }
    private fun original(): Map<String, Any?> {
        assertFalse(File(directory, "typed-export-source/ForeignExportRegistration.hs").exists())
        assertEquals("7", verified(File(directory, "logs/managed-export-registration-native-oracle.stdout")).trim())
        return Json.parse(verified(core)) as Map<String, Any?>
    }
    private fun request(original: Map<String, Any?>, backend: String) = Json.stringify(mapOf(
        "modules" to listOf(original), "entry" to "probe", "strictLink" to true, "backend" to backend))

    @Test fun retainedOriginalCafIsNotEvaluatedAndFirstInstalledKernelStillRuns() {
        for (backend in listOf("ast", "bytecode")) Context.newBuilder("thc").allowExperimentalOptions(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.CompilationFailureAction", "Throw").option("compiler.Inlining", "false")
            .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                val module = original()
                val entry = context.eval("thc", request(module, backend))
                context.enter()
                try {
                    val registry = Language.currentState().foreignRoots
                    val program = registry.programs().single()
                    val roots = registry.retained(program)!!
                    val declarations = (module["staticForeignExports"] as Map<String, Any?>)["exports"] as List<Map<String, Any?>>
                    val bottom = declarations.single { it["symbol"] == "thc_registration_bottom" }["binder"] as Map<String, Any?>
                    val id = "${bottom["unit"]}:${bottom["module"]}.${bottom["occurrence"]}"
                    val thunk = roots.getValue(id) as Thunk
                    assertEquals(0, thunk.state)
                    assertSame(program.entryValue(id), thunk)
                    assertEquals(2, roots.size)
                    assertTrue(context.getBindings("thc").memberKeys.isEmpty(), "Registration is not a callable namespace")
                    repeat(4) { assertEquals(7L, entry.execute(7).asLong()) }
                    val target = program.entryTarget("probe")
                    val cls = Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget")
                    cls.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                    assertEquals(true, cls.getMethod("isValidLastTier").invoke(target))
                    val runtime = Truffle.getRuntime()
                    runtime.javaClass.getMethod("bypassedInstalledCode", cls).invoke(runtime, target)
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    assertEquals(19L, entry.execute(19).asLong())
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before)
                    assertEquals(0, thunk.state, "Neither registration nor a different kernel may force the exported CAF")
                } finally { context.leave() }
            }
    }

    @Test fun sharedParseRootsHaveContextOwnedProgramsAndReleaseOnDisposal() {
        val original = original()
        for (backend in listOf("ast", "bytecode")) Engine.create().use { engine ->
            val first = Context.newBuilder("thc").engine(engine).build()
            val second = Context.newBuilder("thc").engine(engine).build()
            lateinit var registry: ManagedForeignRoots
            lateinit var program: ExecutableProgram
            try {
                val source = Source.newBuilder("thc", request(original, backend), "registration").cached(true).buildLiteral()
                assertEquals(3L, first.eval(source).execute(3).asLong())
                first.enter()
                try { registry = Language.currentState().foreignRoots; program = registry.programs().single() }
                finally { first.leave() }
                assertEquals(5L, second.eval(source).execute(5).asLong())
                second.enter()
                try {
                    assertNotSame(program, Language.currentState().foreignRoots.programs().single())
                    assertThrows(RuntimeFault::class.java) { registry.retain(program, emptyList()) }
                } finally { second.leave() }
                first.close()
                assertEquals(0, registry.size())
                assertNull(registry.retained(program))
                second.enter()
                try { assertEquals(1, Language.currentState().foreignRoots.size()) } finally { second.leave() }
            } finally { first.close(); second.close() }
        }
    }

    @Test fun originalReimportAndOtherNativeCallsRemainUnsupported() {
        val source = original()
        for (backend in listOf("ast", "bytecode")) Context.create("thc").use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((entry, symbol) in listOf("callbackAgain" to "thc_registration_entry", "unsupported" to "abort")) {
                    val linked = CoreModules.reachable(CoreModules.merge(listOf(source)), entry, true)
                    val error = assertThrows(RuntimeException::class.java) {
                        if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
                    }
                    assertTrue(error.message.orEmpty().contains(symbol), error.message)
                }
                assertEquals(0, Language.currentState().foreignRoots.size())
            } finally { context.leave() }
        }
    }

    @Test fun strictAuditorChecksRetainedBodiesAndDoesNotEnableNativeCalls() {
        original()
        val output = File(directory, "registration-runtime-audit.json")
        fun audit(entry: String): Map<String, Any?> {
            val process = ProcessBuilder("python3", File(root, "scripts/audit-core.py").path,
                core.path, "--entry", entry, "--output", output.path).directory(root).redirectErrorStream(true).start()
            val log = process.inputStream.bufferedReader().readText()
            val status = process.waitFor()
            assertEquals(if (entry == "probe") 0 else 1, status, log)
            return Json.parse(output.readText()) as Map<String, Any?>
        }
        val good = audit("probe")
        assertEquals(true, good["accepted"])
        assertEquals(2, (good["retainedExports"] as List<*>).size)
        for (entry in listOf("callbackAgain", "unsupported")) {
            val bad = audit(entry)
            assertTrue((bad["issues"] as List<Map<String, Any?>>).any { it["code"] == "foreign-call" })
        }
    }
}
