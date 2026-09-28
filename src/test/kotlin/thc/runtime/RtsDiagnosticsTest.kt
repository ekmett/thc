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
    private fun context(output: OutputStream, native: Boolean = false,
                        stdout: OutputStream = ByteArrayOutputStream()) = Context.newBuilder("thc").err(output).out(stdout)
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
        return program(language, backend, module)
    }
    private fun program(language: Language, backend: String, module: Map<String, Any?>): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun format(operation: String) = cstring((if (operation == "debugBelch2") "%s\n" else "%s").toByteArray())
    private fun bytes(values: List<Long>) = values.map { it.toByte() }.toByteArray()
    private fun cstring(bytes: ByteArray) = ManagedAddress.fromByteArray(bytes + byteArrayOf(0))
    private fun fixture(): Map<String, Any?> {
        val root = File(System.getProperty("thc.projectRoot"))
        val prefix = "build/rts-diagnostics"
        val manifest = Json.parse(File(root, "$prefix/manifest.json").readText()) as Map<String, Any?>
        OriginalStdioChecks.hashes(root, manifest["inputHashes"], setOf("compiler/test-fixtures/RtsDiagnosticsNative.hs",
            "test/haskell-fixtures/RtsDiagnosticFixtures.hs"))
        val cases = listOf("ascii", "empty", "bytes", "nul", "newline")
        val nativeCases = cases + cases.map { "debug-$it" } + listOf("trace-nul", "stack", "heap")
        OriginalStdioChecks.hashes(root, manifest["artifactHashes"], setOf("$prefix/oracle.json") +
            nativeCases.flatMap { listOf("$prefix/logs/$it.stderr", "$prefix/logs/$it.stdout") }, "$prefix/")
        return (Json.parse(File(root, "$prefix/oracle.json").readText()) as Map<String, Any?>).also {
            assertEquals(true, it["nativeCallsReturned"])
            assertEquals(true, it["nativeStdoutOnlyHarnessMarkers"])
            assertEquals(true, it["overflowSizesAreBackendSpecific"])
        }
    }

    @Test fun nativeCStringBytesMatchBothBackendsAndFirstInstalledCallsReturn() {
        val fixture = fixture()
        val rows = fixture["cases"] as List<Map<String, Any?>>
        val nativeOutput = (fixture["nativeOutput"] as List<Map<String, Any?>>).associateBy { it["name"] }
        assertEquals(listOf("ascii", "empty", "bytes", "nul", "newline"), rows.map { it["name"] })
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            val stdout = ByteArrayOutputStream()
            context(output, stdout = stdout).use { context -> entered(context) { language ->
                val threads = Language.currentState().threads
                threads.enterCurrent()
                try {
                    for (operation in listOf("errorBelch2", "debugBelch2", "reportStackOverflow", "reportHeapOverflow")) {
                        val program = program(language, backend, call(operation))
                        val target = program.entryTarget("entry")
                        fun exercise(compiled: Boolean) {
                            val selected = if (operation.endsWith("Belch2")) rows else listOf(emptyMap())
                            for (row in selected) {
                                output.reset()
                                val arguments: Array<Any?> = when (operation) {
                                    "errorBelch2", "debugBelch2" -> arrayOf(0L, format(operation),
                                        cstring(bytes(row["bytes"] as List<Long>)).plus(row["offset"] as Long), Unit)
                                    "reportStackOverflow" -> arrayOf(0L, threads.currentIdentity(), Unit)
                                    else -> arrayOf(0L, Unit)
                                }
                                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                                assertEquals(0L, Calls.target(target, arguments))
                                val expected = when (operation) {
                                    "errorBelch2" -> bytes(row["message"] as List<Long>) + byteArrayOf(10)
                                    "debugBelch2" -> bytes(nativeOutput.getValue("debug-${row["name"]}")["stderr"] as List<Long>)
                                    "reportStackOverflow" -> "Stack space overflow (THC guest Java thread ${Thread.currentThread().threadId()}; JVM stack limit unavailable).\n".toByteArray()
                                    else -> "Heap exhausted; JVM maximum heap size is ${Runtime.getRuntime().maxMemory()} bytes.\n".toByteArray()
                                }
                                assertArrayEquals(expected, output.toByteArray(), "$backend/$operation/${row["name"]}")
                                assertEquals(0, stdout.size(), "Native diagnostics write no stdout bytes")
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
                        if (operation == "debugBelch2") {
                            // Native traceIO filters NULs in Haskell, then makes
                            // these two leaf calls. The leaf itself only truncates.
                            output.reset()
                            val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                            for (message in listOf("leftright", "WARNING: previous trace message had null bytes"))
                                assertEquals(0L, Calls.target(target, arrayOf(0L, format(operation),
                                    cstring(message.toByteArray()), Unit)))
                            assertArrayEquals(bytes(nativeOutput.getValue("trace-nul")["stderr"] as List<Long>), output.toByteArray())
                            assertEquals(before + 2, (program.diagnostics().getValue("compiledEntries") as Number).toLong())
                            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target))
                            assertEquals(0, stdout.size())
                        }
                    }
                } finally { threads.leaveCurrent() }
            } }
        }
    }

    @Test fun malformedDescriptorsAndInvalidCarriersRejectBeforeOutput() {
        for (backend in listOf("ast", "bytecode")) {
            val output = ByteArrayOutputStream()
            val stdout = ByteArrayOutputStream()
            context(output, stdout = stdout).use { context -> entered(context) { language ->
                for (operation in listOf("errorBelch2", "debugBelch2")) {
                    val target = program(language, backend, call(operation)).entryTarget("entry")
                    val text = cstring("ok".toByteArray())
                    val validFormat = format(operation)
                    val otherFormat = format(if (operation == "debugBelch2") "errorBelch2" else "debugBelch2")
                    for (args in listOf<Array<Any?>>(arrayOf(0L, validFormat, text, 1L),
                        arrayOf(0L, 1L, text, Unit), arrayOf(0L, validFormat, 1L, Unit),
                        arrayOf(0L, otherFormat, text, Unit),
                        arrayOf(0L, cstring("%d".toByteArray()), text, Unit),
                        arrayOf(0L, ManagedAddress.fromByteArray(byteArrayOf(37, 115)), text, Unit),
                        arrayOf(0L, validFormat, ManagedAddress.fromByteArray(byteArrayOf(1, 2)), Unit),
                        arrayOf(0L, ManagedAddress.nullAddress(), text, Unit),
                        arrayOf(0L, validFormat, ManagedAddress.nullAddress(), Unit))) {
                        assertThrows(RuntimeFault::class.java) { Calls.target(target, args) }
                        assertEquals(0, output.size()); assertEquals(0, stdout.size())
                    }
                    for ((key, value) in listOf("safety" to "safe", "arity" to 4L,
                        "suppliedArity" to 2L, "convention" to "capi", "schema" to 2L)) {
                        val broken = Json.parse(Json.stringify(call(operation))) as MutableList<Any?>
                        (((broken[6] as MutableMap<String, Any?>)["foreignCall"]) as MutableMap<String, Any?>)[key] = value
                        assertThrows(RuntimeFault::class.java) { program(language, backend, broken) }
                    }
                    for (unit in listOf("main", "ghc-internal-9.1401.0-inplace")) {
                        val broken = Json.parse(Json.stringify(call(operation))) as MutableList<Any?>
                        val descriptor = (broken[6] as Map<*, *>)["foreignCall"] as Map<*, *>
                        (descriptor["target"] as MutableMap<String, Any?>)["unit"] = unit
                        assertThrows(RuntimeFault::class.java) { program(language, backend, broken) }
                    }
                    val source = mapOf("sourceFiles" to emptyList<Any>(), "sourceSpans" to emptyList<Any>())
                    assertThrows(RuntimeFault::class.java) {
                        program(language, backend, OriginalStdioChecks.rawModule(call(operation), source, 2))
                    }
                    for (mutation in listOf("state-producer", "bound-head", "singleton-state-result")) {
                        val module = Json.parse(Json.stringify(OriginalStdioChecks.rawModule(call(operation), source))) as Map<String, Any?>
                        val binding = (module["bindings"] as List<Map<String, Any?>>).single()
                        val body = (binding["expr"] as List<*>)[2] as List<*>
                        val call = body[1] as MutableList<Any?>
                        when (mutation) {
                            "state-producer" -> (call[2] as MutableList<Any?>)[2] =
                                listOf("lit", "int", "0", mapOf("rep" to scalar(null)))
                            "bound-head" -> (call[1] as MutableList<Any?>)[1] = "p0"
                            else -> ((call[6] as Map<*, *>)["foreignCall"] as MutableMap<String, Any?>)["resultRep"] = scalar(null, false)
                        }
                        assertThrows(RuntimeFault::class.java) { program(language, backend, module) }
                    }
                }
                val stack = program(language, backend, call("reportStackOverflow")).entryTarget("entry")
                assertThrows(RuntimeFault::class.java) { Calls.target(stack, arrayOf(0L, Any(), Unit)) }
                assertEquals(0, output.size()); assertEquals(0, stdout.size())
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
            assertDoesNotThrow { RtsDiagnostics.report(null, RtsDiagnosticOp.DEBUG,
                format("debugBelch2"), cstring("message".toByteArray())) }
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
                for (operation in listOf(RtsDiagnosticOp.ERROR, RtsDiagnosticOp.DEBUG)) {
                    RtsDiagnostics.report(null, operation, format(operation.symbol), alias)
                    assertArrayEquals(byteArrayOf(120, 10), output.toByteArray()); output.reset()
                    context(output).use { second -> entered(second) {
                        assertThrows(RuntimeFault::class.java) { RtsDiagnostics.report(null, operation, format(operation.symbol), alias) }
                        assertEquals(0, output.size())
                    } }
                }
            } finally { state.nativeAllocations.free(address) }
            for (operation in listOf(RtsDiagnosticOp.ERROR, RtsDiagnosticOp.DEBUG))
                assertThrows(RuntimeFault::class.java) { RtsDiagnostics.report(null, operation, format(operation.symbol), alias) }
            assertEquals(0, output.size())
        } }
    }
}
