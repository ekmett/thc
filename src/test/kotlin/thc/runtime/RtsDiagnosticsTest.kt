// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import thc.Json
import thc.Language
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream

class RtsDiagnosticsTest {
    private fun context(output: OutputStream, native: Boolean = false) = Context.newBuilder("thc").err(output)
        .allowNativeAccess(native).allowExperimentalOptions(true).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun <T> entered(context: Context, action: (Language) -> T): T {
        context.initialize("thc"); context.enter()
        return try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) }
        finally { context.leave() }
    }
    private fun scalar(rep: String?, evaluated: Boolean = true) = mapOf("kind" to when (rep) {
        null -> "void"; "AddrRep" -> "address"; "BoxedRep (Just Unlifted)" -> "object"; else -> "closure"
    }, "primReps" to listOfNotNull(rep), "evaluated" to evaluated)
    private fun tuple(evaluated: Boolean = true) = mapOf("kind" to "unknown", "primReps" to emptyList<String>(),
        "aggregate" to "unboxed-tuple", "components" to listOf(scalar(null)), "evaluated" to evaluated)
    /** ABI controls are synthetic; the separately compiled native oracle calls the original symbols. */
    private fun call(operation: String): List<Any?> {
        val reps = when (operation) {
            "reportStackOverflow" -> listOf("BoxedRep (Just Unlifted)", null)
            "reportHeapOverflow" -> listOf(null)
            else -> listOf("AddrRep", "AddrRep", null)
        }
        val descriptor = mapOf("schema" to 1L, "target" to mapOf("kind" to "static", "symbol" to operation,
            "unit" to "ghc-internal", "isFunction" to true), "convention" to "ccall", "safety" to "unsafe",
            "arity" to reps.size.toLong(), "suppliedArity" to reps.size.toLong(),
            "argumentReps" to reps.map { scalar(it, false) }, "resultRep" to tuple(false))
        return listOf("app", listOf("var", "foreign", mapOf("rep" to scalar("BoxedRep (Just Lifted)"))),
            reps.mapIndexed { i, rep -> listOf("var", "p$i", mapOf("rep" to scalar(rep))) },
            reps.map { false }, false, false, mapOf("rep" to tuple(), "foreignCall" to descriptor))
    }
    private fun program(language: Language, backend: String, call: List<Any?>): ExecutableProgram {
        val module = OriginalStdioChecks.rawModule(call, mapOf("sourceFiles" to emptyList<Any>(), "sourceSpans" to emptyList<Any>()))
        return if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    }
    private fun bytes(values: List<Long>) = values.map { it.toByte() }.toByteArray()
    private fun cstring(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + byteArrayOf(0))
    private fun fixture(): Map<String, Any?> {
        val root = File(System.getProperty("thc.projectRoot"))
        val prefix = "build/rts-diagnostics"
        val manifest = Json.parse(File(root, "$prefix/manifest.json").readText()) as Map<String, Any?>
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/RtsDiagnosticsNative.hs",
            "test/haskell-fixtures/RtsDiagnosticFixtures.hs"))
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            listOf("ascii", "empty", "bytes", "nul", "newline", "stack", "heap").map { "$prefix/logs/$it.stderr" }, "$prefix/")
        return (Json.parse(File(root, "$prefix/oracle.json").readText()) as Map<String, Any?>).also {
            assertEquals(true, it["nativeCallsReturned"])
            assertEquals(true, it["overflowSizesAreBackendSpecific"])
        }
    }

    @Test fun nativeCStringBytesMatchBothBackendsAndFirstInstalledCallsReturn() {
        val rows = fixture()["cases"] as List<Map<String, Any?>>
        assertEquals(listOf("ascii", "empty", "bytes", "nul", "newline"), rows.map { it["name"] })
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            context(output).use { context -> entered(context) { language ->
                val threads = Language.currentState().threads
                threads.enterCurrent()
                try {
                    for (operation in listOf("errorBelch2", "reportStackOverflow", "reportHeapOverflow")) {
                        val program = program(language, backend, call(operation))
                        val target = program.entryTarget("entry")
                        fun exercise(compiled: Boolean) {
                            val selected = if (operation == "errorBelch2") rows else listOf(emptyMap())
                            for (row in selected) {
                                output.reset()
                                val arguments: Array<Any?> = when (operation) {
                                    "errorBelch2" -> arrayOf(0L, cstring("%s".toByteArray()),
                                        cstring(bytes(row["bytes"] as List<Long>)).plus(row["offset"] as Long), Unit)
                                    "reportStackOverflow" -> arrayOf(0L, threads.currentIdentity(), Unit)
                                    else -> arrayOf(0L, Unit)
                                }
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(0L, Calls.target(target, arguments))
                                val expected = when (operation) {
                                    "errorBelch2" -> bytes(row["message"] as List<Long>) + byteArrayOf(10)
                                    "reportStackOverflow" -> "Stack space overflow (THC guest Java thread ${Thread.currentThread().threadId()}; JVM stack limit unavailable).\n".toByteArray()
                                    else -> "Heap exhausted; JVM maximum heap size is ${Runtime.getRuntime().maxMemory()} bytes.\n".toByteArray()
                                }
                                assertArrayEquals(expected, output.toByteArray(), "$backend/$operation/${row["name"]}")
                                if (compiled) {
                                    assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                                    assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                                }
                                val handoff = language.handoffState.get()
                                assertEquals(0, handoff.arguments.depth); assertEquals(0, handoff.results.depth)
                                assertEquals(0, handoff.arguments.retainedReferences()); assertEquals(0, handoff.results.retainedReferences())
                            }
                        }
                        exercise(false)
                        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                        exercise(true)
                    }
                } finally { threads.leaveCurrent() }
            } }
        }
    }

    @Test fun malformedDescriptorsAndInvalidCarriersRejectBeforeOutput() {
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            context(output).use { context -> entered(context) { language ->
                val target = program(language, backend, call("errorBelch2")).entryTarget("entry")
                val text = cstring("ok".toByteArray())
                val format = cstring("%s".toByteArray())
                for (args in listOf<Array<Any?>>(arrayOf(0L, format, text, 1L), arrayOf(0L, cstring("%d".toByteArray()), text, Unit),
                    arrayOf(0L, format, ManagedAddress.fromByteArray(byteArrayOf(1, 2)), Unit),
                    arrayOf(0L, format, ManagedAddress.nullAddress(), Unit))) {
                    assertThrows(RuntimeFault::class.java) { Calls.target(target, args) }
                    assertEquals(0, output.size())
                }
                val stack = program(language, backend, call("reportStackOverflow")).entryTarget("entry")
                assertThrows(RuntimeFault::class.java) { Calls.target(stack, arrayOf(0L, Any(), Unit)) }
                for ((key, value) in listOf("safety" to "safe", "arity" to 4L, "convention" to "capi")) {
                    val broken = Json.parse(Json.stringify(call("errorBelch2"))) as MutableList<Any?>
                    (((broken[6] as MutableMap<String, Any?>)["foreignCall"]) as MutableMap<String, Any?>)[key] = value
                    assertThrows(RuntimeFault::class.java) { program(language, backend, broken) }
                }
                assertEquals(0, output.size())
            } }
        }
    }

    @Test fun foreignThreadIdentityRejectsAndStreamErrorsReturn() {
        val output = ByteArrayOutputStream()
        context(output).use { first -> entered(first) {
            val state = Language.currentState()
            state.threads.enterCurrent()
            try {
                val thread = state.threads.currentIdentity()
                context(output).use { second -> entered(second) {
                    assertThrows(RuntimeFault::class.java) { RtsDiagnostics.report(null, RtsDiagnosticOp.STACK, thread, null) }
                    assertEquals(0, output.size())
                } }
            } finally { state.threads.leaveCurrent() }
        } }
        val broken = object : OutputStream() { override fun write(value: Int) { throw IOException("closed") } }
        context(broken).use { context -> entered(context) {
            assertDoesNotThrow { RtsDiagnostics.report(null, RtsDiagnosticOp.HEAP, null, null) }
        } }
    }

    @Test fun ownedNativeCStringAliasesRejectCrossContextAndExpiredStorage() {
        assumeTrue(System.getProperty("os.name") == "Linux" && System.getProperty("os.arch") in setOf("amd64", "x86_64"),
            "Owned native malloc currently has the verified Linux x86_64 ABI")
        val output = ByteArrayOutputStream()
        context(output, true).use { first -> entered(first) {
            val state = Language.currentState()
            val address = state.nativeAllocations.malloc(4)
            val alias = address.plus(1)
            try {
                alias.writeWord8(0, 120); alias.writeWord8(1, 0)
                RtsDiagnostics.report(null, RtsDiagnosticOp.ERROR, cstring("%s".toByteArray()), alias)
                assertArrayEquals(byteArrayOf(120, 10), output.toByteArray()); output.reset()
                context(output).use { second -> entered(second) {
                    assertThrows(RuntimeFault::class.java) { RtsDiagnostics.report(null, RtsDiagnosticOp.ERROR, cstring("%s".toByteArray()), alias) }
                    assertEquals(0, output.size())
                } }
            } finally { state.nativeAllocations.free(address) }
            assertThrows(RuntimeFault::class.java) { RtsDiagnostics.report(null, RtsDiagnosticOp.ERROR, cstring("%s".toByteArray()), alias) }
            assertEquals(0, output.size())
        } }
    }
}
