// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.Truffle
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.frame.FrameDescriptor
import com.oracle.truffle.api.frame.VirtualFrame
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

class ExceptionResultLayoutsNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/exception-result-layouts")

    companion object {
        private val entries = listOf("int8", "word8", "int16", "word16", "int32", "word32",
            "int64", "word64", "float", "double", "empty", "nested", "sum", "vector",
            "unlifted", "unliftedPayload").map { it + "Result" }
        private val stages = if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) listOf("pre")
            else listOf("pre", "post")
        @JvmStatic fun cases(): Stream<Arguments> = stages.flatMap { stage ->
            listOf("ast", "bytecode").flatMap { backend -> entries.map { Arguments.of(stage, backend, it) } }
        }.stream()
    }

    private fun module(stage: String): Map<String, Any?> = Json.parse(
        File(directory, "$stage/core/ExceptionResultLayoutsAudit.json").readText()) as Map<String, Any?>

    private data class Row(val mode: Long, val input: Long, val expected: Long)
    private fun oracle(): Map<String, List<Row>> {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        assertEquals("9.14.1", manifest["ghc"])
        assertEquals(entries, manifest["entries"])
        assertEquals(stages, manifest["stages"], "Only stages actually emitted by the host GHC pipeline")
        assertEquals("native-boxed-effects-independent-layout-model", manifest["oracleKind"])
        for (kind in listOf("inputHashes", "artifactHashes"))
            for ((path, expected) in manifest[kind] as Map<String, String>) {
                val file = File(root, path)
                assertTrue(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()))
                val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(expected, hash, "$kind/$path")
            }
        val lines = File(root, manifest["oracle"] as String).readLines().map { it.split('\t') }
        assertEquals(141, lines.size)
        return lines.groupBy { it[0] }.mapValues { (name, rows) ->
            rows.map { Row(it[1].toLong(), it[2].toLong(), it[3].toLong()) }.also { values ->
                assertEquals(if (name == "unliftedPayloadResult") 6 else 9, values.size)
                for (row in values) {
                    val value = row.input + if (row.mode == 0L) 7 else 34
                    val transformed = when (name) {
                        "int8Result" -> value.toByte().toLong()
                        "word8Result" -> value and 255
                        "int16Result" -> value.toShort().toLong()
                        "word16Result" -> value and 65535
                        "int32Result" -> value.toInt().toLong()
                        "word32Result" -> value and 0xffffffffL
                        "emptyResult" -> 0
                        "nestedResult" -> 2 * value + 1
                        "vectorResult" -> 4 * value
                        else -> value
                    }
                    assertEquals(transformed + 101, row.expected, "$name/$row")
                }
            }
        }
    }

    private fun calls(value: Any?): List<List<Any?>> = when (value) {
        is Map<*, *> -> value.values.flatMap(::calls)
        is List<*> -> (if (value.firstOrNull() == "app" && (value[1] as? List<*>)?.firstOrNull() == "prim")
            listOf(value) else emptyList()) + value.flatMap(::calls)
        else -> emptyList()
    }

    @Test fun genuineProofsKeepResultShapeAndIndependentPayloadLevity() {
        oracle()
        for (stage in stages) for (entry in entries) {
            val linked = CoreModules.reachable(module(stage), entry, true)
            val primitives = calls(linked).filter { (it[1] as List<*>)[1] in setOf(
                "catch#", "raiseIO#", "maskAsyncExceptions#", "maskUninterruptible#", "unmaskAsyncExceptions#") }
            assertTrue(primitives.isNotEmpty(), "$stage/$entry")
            for (call in primitives) {
                val name = (call[1] as List<*>)[1] as String
                val arguments = (call[2] as List<List<Any?>>).map(CoreRepresentations::expression)
                val flags = call[3] as List<*>
                val result = CoreRepresentations.expression(call)
                assertEquals(2, result.components!!.size, "$stage/$entry/$name logical arity")
                val value = result.components[1]
                when (entry) {
                    "emptyResult" -> assertTrue(value.isEmptyTuple)
                    "nestedResult" -> assertTrue(value.isTuple && value.components!![1].isTuple)
                    "sumResult" -> assertTrue(value.isSum)
                    "vectorResult" -> assertTrue(value.isVector)
                    "unliftedResult" -> assertEquals(listOf("BoxedRep (Just Unlifted)"), value.primReps)
                }
                CoreSynchronousExceptions.validate(name, arguments, flags, result)
                if (entry == "unliftedPayloadResult" && name == "raiseIO#") {
                    assertEquals(listOf("BoxedRep (Just Unlifted)"), arguments[0].primReps)
                    assertEquals(listOf(false, false), flags)
                    assertThrows(RuntimeFault::class.java) {
                        CoreSynchronousExceptions.validate(name, arguments, listOf(true, false), result)
                    }
                }
                assertThrows(RuntimeFault::class.java) {
                    CoreSynchronousExceptions.validate(name, arguments, flags,
                        result.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, result.components + result.components.last(), originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) })
                }
            }
            val audit = Json.parse(File(directory, "$stage/$entry-audit.json").readText()) as Map<*, *>
            assertEquals(true, audit["accepted"], "$stage/$entry")
            assertEquals(emptyList<Any>(), audit["issues"])
        }
    }

    @Test fun unknownIsNotAZeroWidthResultAndUnknownPayloadLevityIsRejected() {
        val state = CoreRepresentation(CoreKind.VOID, true, true, emptyList())
        val closure = CoreRepresentation(CoreKind.CLOSURE, true, true, listOf("BoxedRep (Just Lifted)"))
        val empty = CoreRepresentation(CoreKind.UNKNOWN, true, true, emptyList(), emptyList())
        fun tuple(value: CoreRepresentation) = CoreRepresentation(CoreKind.UNKNOWN, true, true,
            value.primReps, listOf(state, value))
        CoreSynchronousExceptions.validate("catch#", listOf(closure, closure, state),
            listOf(true, true, false), tuple(empty))
        for (value in listOf(CoreRepresentation.UNKNOWN, empty.let { originalProof -> originalProof.copy(originalProof.kind, originalProof.evaluated, originalProof.present, originalProof.primReps, null, originalProof.vector, originalProof.alternatives, originalProof.tagSlot, originalProof.alternativeSlots) },
            CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep Nothing"))))
            assertThrows(RuntimeFault::class.java) {
                CoreSynchronousExceptions.validate("catch#", listOf(closure, closure, state),
                    listOf(true, true, false), tuple(value))
            }
        for (payload in listOf(CoreRepresentation.UNKNOWN,
            CoreRepresentation(CoreKind.LONG, true, true, listOf("IntRep")),
            CoreRepresentation(CoreKind.OBJECT, false, true, listOf("BoxedRep Nothing"))))
            assertThrows(RuntimeFault::class.java) {
                CoreSynchronousExceptions.validate("raiseIO#", listOf(payload, state), listOf(false, false), tuple(empty))
            }
    }

    @Test fun boxedUnliftedRaiseKeepsTheExactPayloadAndConsumesStateOnce() {
        val payload = Any()
        var consumed = 0
        val state = CoreRepresentation(CoreKind.VOID, true, true, emptyList())
        val value = CoreRepresentation(CoreKind.OBJECT, true, true, listOf("BoxedRep (Just Unlifted)"))
        val result = CoreRepresentation(CoreKind.UNKNOWN, true, true, value.primReps, listOf(state, value))
        CoreSynchronousExceptions.validate("raiseIO#", listOf(value, state), listOf(false, false), result)
        val raise = RaiseIOException(object : Expr() {
            override fun execute(frame: VirtualFrame): Any = payload
        }, object : Expr() {
            override fun execute(frame: VirtualFrame): Any { consumed++; return Unit }
        }, result)
        val frame = Truffle.getRuntime().createVirtualFrame(emptyArray(), FrameDescriptor.newBuilder().build())
        val exception = assertThrows(GuestException::class.java) { raise.executeTuple(frame, intArrayOf(), 0) }
        assertSame(payload, exception.payload)
        assertEquals(1, consumed)
    }

    @ParameterizedTest(name = "{0}/{1}/{2}")
    @MethodSource("cases")
    fun nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall(stage: String, backend: String, entry: String) {
        val selected = oracle().getValue(entry)
        val failures = CopyOnWriteArrayList<String>()
        val runtime = Truffle.getRuntime() as OptimizedTruffleRuntime
        val listener = object : OptimizedTruffleRuntimeListener {
            override fun onCompilationFailed(target: OptimizedCallTarget, reason: String?, bailout: Boolean,
                permanentBailout: Boolean, tier: Int, lazyStackTrace: Supplier<String>?) { failures += "$target: $reason" }
        }
        runtime.addListener(listener)
        try {
            Context.newBuilder("thc").allowExperimentalOptions(true).allowCreateThread(true)
                .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
                .option("engine.Splitting", "false").option("engine.CompilationFailureAction", "Throw")
                .option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
                    context.initialize("thc"); context.enter()
                    try {
                        val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                        val linked = CoreModules.reachable(module(stage), entry, true) + ("instrument" to true)
                        val program: ExecutableProgram = if (backend == "ast") Program(language, linked, true)
                            else BytecodeProgram(language, linked, true)
                        val target = program.entryTarget(entry)
                        val function = context.asValue(EntryValue(program, entry, 2))
                        val label = "$stage/$backend/$entry"
                        fun check(row: Row) {
                            assertEquals(row.expected, function.execute(row.mode, row.input).asLong(), "$label/$row")
                            assertEquals(MaskingState.UNMASKED, SynchronousMasking.current(target.rootNode), label)
                            assertEquals(0, language.handoffState.get().arguments.depth, label)
                            assertEquals(0, language.handoffState.get().results.depth, label)
                        }
                        selected.forEach(::check)
                        assertTrue(function.invokeMember("compile").asBoolean(), label)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$label installed")
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        check(selected.last()) // Immediate first invocation; no settling/recovery call.
                        assertTrue((program.diagnostics().getValue("compiledEntries") as Number).toLong() > before, label)
                        assertSame(target, program.entryTarget(entry), label)
                        assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target),
                            "$label retained after first installed call")
                        selected.forEach(::check)
                        assertEquals(0L, program.diagnostics()["unsupportedTraps"], label)
                        assertEquals(0L, program.diagnostics()["blackholes"], label)
                    } finally { context.leave() }
                }
            assertEquals(emptyList<String>(), failures.toList(), "No concealed diagnostic compilation retry")
        } finally { runtime.removeListener(listener) }
    }

    @Test fun floatingAndNestedCaptureRootsInstallWithoutDiagnosticRetry() {
        nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall("pre", "ast", "floatResult")
        nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall("pre", "bytecode", "nestedResult")
    }

    @Test fun asyncVectorOperandReadsItsRawCarrierBeforeAndAfterInstallation() {
        nativeModelEffectsAndLayoutsSurviveTheFirstInstalledCall("pre", "ast", "vectorResult")
    }
}
