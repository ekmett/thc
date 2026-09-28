// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.runtime.OptimizedCallTarget
import com.oracle.truffle.runtime.OptimizedTruffleRuntime
import com.oracle.truffle.runtime.OptimizedTruffleRuntimeListener
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import thc.CoreModules
import thc.EntryValue
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.function.Supplier
import java.util.stream.Stream

class ScalarExceptionResultsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/scalar-exception-results")

    companion object {
        private val entries = listOf("normal", "throw", "interrupt").flatMap { prefix ->
            listOf("Int", "Word", "Addr").map { prefix + it }
        }
        @JvmStatic fun cases(): Stream<Arguments> = listOf("pre", "post").flatMap { stage ->
            listOf("ast", "bytecode").flatMap { backend -> entries.flatMap { entry ->
                (if (entry.startsWith("interrupt")) listOf(true) else listOf(false, true)).map { async ->
                    Arguments.of(stage, backend, entry, async)
                }
            } }
        }.stream()
    }

    private fun module(stage: String): Map<String, Any?> = Json.parse(
        File(directory, "$stage/core/ScalarExceptionResultsAudit.json").readText()) as Map<String, Any?>

    private fun oracle(): Map<String, List<Pair<Long, Long>>> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries, manifest["entries"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, hash, "$kind/$path")
            }
        val lines = File(root, manifest["oracle"] as String).readLines().map { it.split('\t') }
        assertEquals(36, lines.size)
        return lines.groupBy { it[0] }.mapValues { (name, rows) ->
            val offset = when {
                name == "normalInt" -> 59L
                name.startsWith("normal") -> 57L
                name.startsWith("throw") -> 34L
                else -> 135L
            }
            rows.map { it[1].toLong() to it[2].toLong() }.also { values ->
                assertEquals(listOf(-129L, -17L, 0L, 23L), values.map { it.first })
                values.forEach { (input, output) -> assertEquals(input + offset, output, name) }
            }
        }
    }

    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::calls)
        is List<*> -> (if (value.firstOrNull() == "app" && (value[1] as? List<*>)?.firstOrNull() == "prim")
            listOf(value) else emptyList()) + value.flatMap(::calls)
        else -> emptyList()
    }

    @Test fun genuineScalarProofsAndMalformedBoundaries() {
        oracle()
        for (stage in listOf("pre", "post")) for (entry in entries) {
            val linked = CoreModules.reachable(module(stage), entry, true)
            val primitives = calls(linked).filter { (it[1] as List<*>)[1] in setOf(
                "catch#", "raiseIO#", "maskAsyncExceptions#", "maskUninterruptible#", "unmaskAsyncExceptions#") }
            assertTrue(primitives.isNotEmpty(), "$stage/$entry retains original exception primops")
            val expected = when { entry.endsWith("Word") -> "WordRep"; entry.endsWith("Addr") -> "AddrRep"; else -> "IntRep" }
            for (call in primitives) {
                val name = (call[1] as List<*>)[1] as String
                val arguments = (call[2] as List<List<Any?>>).map(CoreRepresentations::expression)
                val flags = call[3] as List<*>
                val result = CoreRepresentations.expression(call)
                assertEquals(listOf(expected), result.primReps, "$stage/$entry/$name")
                CoreSynchronousExceptions.validate(name, arguments, flags, result)
                val components = result.components!!
                for (broken in listOf(
                    result.copy(components = components.reversed()),
                    result.copy(components = components + components.last()),
                    result.copy(primReps = listOf("DoubleRep"))
                )) assertThrows(RuntimeFault::class.java, {
                    CoreSynchronousExceptions.validate(name, arguments, flags, broken)
                }, "$stage/$entry/$name malformed result")
                // Scalar carrier/repr disagreements are checked by the generic
                // proof parser, before the primop consumes the lowered layout.
                assertThrows(RuntimeFault::class.java) {
                    CoreRepresentations.parse(mapOf("kind" to "float", "evaluated" to true,
                        "primReps" to components[1].primReps))
                }
                CoreSynchronousExceptions.validate(name, arguments, flags,
                    result.copy(primReps = listOf("DoubleRep"), components = listOf(components[0],
                        CoreRepresentation(CoreKind.DOUBLE, true, true, listOf("DoubleRep")))))
                assertThrows(RuntimeFault::class.java) {
                    CoreSynchronousExceptions.validate(name, arguments, flags.dropLast(1) + true, result)
                }
            }
            val audit = Json.parse(File(directory, "$stage/$entry-audit.json").readText()) as Map<*, *>
            assertEquals(true, audit["accepted"], "$stage/$entry")
            assertEquals(emptyList<Any>(), audit["issues"])
        }
    }

    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)

    @ParameterizedTest(name = "{0}/{1}/{2}/async={3}")
    @MethodSource("cases")
    fun nativeValuesAndMaskRestorationSurviveTheFirstInstalledCall(stage: String, backend: String,
                                                                 entry: String, async: Boolean) {
        val selected = oracle().getValue(entry)
        Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
            .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
            .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
            .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val linked = CoreModules.reachable(module(stage), entry, true) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, linked, async)
                        else BytecodeProgram(language, linked, async)
                    val target = program.entryTarget(entry)
                    val function = context.asValue(EntryValue(program, entry, 1))
                    val label = "$stage/$backend/$entry/async=$async"
                    fun check(row: Pair<Long, Long>) {
                        assertEquals(row.second, function.execute(row.first).asLong(), label)
                        assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.rootNode), label)
                        assertEquals(0, language.handoffState.get().arguments.depth, label)
                        assertEquals(0, language.handoffState.get().results.depth, label)
                    }
                    selected.forEach(::check)
                    assertTrue(function.invokeMember("compile").asBoolean(), label)
                    valid(target, "$label installed")
                    val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                    check(selected.last()) // No settling invocation after installation.
                    assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before,
                        "$label first installed call entered guest code")
                    assertSame(target, program.entryTarget(entry), "$label same guest identity")
                    valid(target, "$label retained after first installed call")
                    assertEquals(0L, program.diagnostics()["unsupportedTraps"], label)
                    assertEquals(0L, program.diagnostics()["blackholes"], label)
                } finally { context.leave() }
            }
    }

    @Test fun bytecodeAsyncNestedMasksCompileWithoutDiagnosticRetry() {
        val failures = CopyOnWriteArrayList<String>()
        val runtime = Truffle.getRuntime() as OptimizedTruffleRuntime
        val listener = object : OptimizedTruffleRuntimeListener {
            override fun onCompilationFailed(target: OptimizedCallTarget, reason: String?, bailout: Boolean,
                permanentBailout: Boolean, tier: Int, lazyStackTrace: Supplier<String>?) {
                failures += "$target: $reason"
            }
        }
        runtime.addListener(listener)
        try {
            nativeValuesAndMaskRestorationSurviveTheFirstInstalledCall("pre", "bytecode", "normalInt", true)
            assertEquals(emptyList<String>(), failures.toList(),
                "Successful installation must not conceal a failed compilation followed by a diagnostic retry")
        } finally { runtime.removeListener(listener) }
    }
}
