// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import com.oracle.truffle.api.bytecode.Instruction
import com.oracle.truffle.api.nodes.DirectCallNode
import com.oracle.truffle.api.nodes.NodeUtil
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.*
import java.io.File
import java.lang.reflect.Modifier
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap

class SimdFamiliesTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val directory = File(root, "build/simd-families")
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "installed")
    }
    private fun activeTargets(entry: RootCallTarget): List<RootCallTarget> {
        val seen = Collections.newSetFromMap(IdentityHashMap<RootCallTarget, Boolean>())
        val result = mutableListOf<RootCallTarget>()
        fun visit(target: RootCallTarget) {
            if (!seen.add(target)) return
            val body = target.rootNode
            val nodes = if (body is BytecodeRoot) listOf(body) + body.bytecodeNode.instructions.flatMap { it.arguments }
                .filter { it.kind == Instruction.Argument.Kind.NODE_PROFILE }.mapNotNull { it.asCachedNode() } else listOf(body)
            for (call in nodes.flatMap { NodeUtil.findAllNodeInstances(it, DirectCallNode::class.java) }) {
                val active = call.currentCallTarget as? RootCallTarget ?: continue
                if (active.rootNode is GuestRoot) visit(active)
            }
            result.add(target)
        }
        visit(entry)
        return result
    }
    private fun count(p: ExecutableProgram) = (p.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth); assertEquals(0, state.arguments.retainedReferences())
        assertEquals(0, state.results.depth); assertEquals(0, state.results.retainedReferences())
    }

    @Test fun generatedCarriersHaveOnlyFinalPrimitiveLanes() {
        for ((carrier, count, primitive) in listOf(Triple(Word64X2::class.java, 2, Long::class.javaPrimitiveType),
            Triple(Word32X8::class.java, 8, Int::class.javaPrimitiveType),
            Triple(Int32X8::class.java, 8, Int::class.javaPrimitiveType), Triple(Int32X16::class.java, 16, Int::class.javaPrimitiveType),
            Triple(FloatX8::class.java, 8, Float::class.javaPrimitiveType), Triple(DoubleX4::class.java, 4, Double::class.javaPrimitiveType))) {
            val fields = carrier.declaredFields
            assertEquals(count, fields.size)
            assertTrue(fields.all { it.type == primitive && Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers) })
            assertEquals((0 until count).map { "lane$it" }.toSet(), fields.map { it.name }.toSet())
        }
    }

    @Test fun exactLaneSignWidthLogicalTupleAndCallingProofsRemainRequired() {
        assertEquals(47, GeneratedVectors.operations.size)
        for ((name, tuple, vector) in listOf(
            Triple("Word64X2", GeneratedVectors.unpackedWord64X2, GeneratedVectors.proofWord64X2),
            Triple("Word32X8", GeneratedVectors.unpackedWord32X8, GeneratedVectors.proofWord32X8),
            Triple("Int32X8", GeneratedVectors.unpackedInt32X8, GeneratedVectors.proofInt32X8),
            Triple("Int32X16", GeneratedVectors.unpackedInt32X16, GeneratedVectors.proofInt32X16),
            Triple("FloatX8", GeneratedVectors.unpackedFloatX8, GeneratedVectors.proofFloatX8),
            Triple("DoubleX4", GeneratedVectors.unpackedDoubleX4, GeneratedVectors.proofDoubleX4))) {
            CoreVectors.validate("pack$name#", listOf(tuple), vector)
            CoreVectors.validate("times$name#", listOf(vector, vector), vector)
            CoreVectors.validate("unpack$name#", listOf(vector), tuple)
            for (index in tuple.components!!.indices) {
                val wrong = tuple.copy(components = tuple.components.mapIndexed { i, proof ->
                    if (i == index) proof.copy(primReps = listOf("WordRep")) else proof })
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate("pack$name#", listOf(wrong), vector) }
            }
            for (wrong in listOf(CoreVectors.proof32, CoreVectors.proof16, CoreVectors.proofFloat, tuple)) {
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate("times$name#", listOf(vector, wrong), vector) }
                assertThrows(RuntimeFault::class.java) { CoreVectors.validate("times$name#", listOf(vector, vector), wrong) }
            }
            for (arity in listOf(0, 1, 3)) assertThrows(RuntimeFault::class.java) {
                CoreVectors.validate("times$name#", List(arity) { vector }, vector)
            }
            assertThrows(UnsupportedCore::class.java) { CoreRepresentations.requireInput(vector) }
        }
        for (flags in listOf(listOf(true), listOf(null), listOf(0L))) assertThrows(RuntimeFault::class.java) {
            CoreVectors.validateFlags(flags)
        }
    }

    // These early gates use actual pre-Tidy Core and the independent model.
    // They are deliberately separate from native evidence and capability enablement.
    @Test fun widerPreparedCoreCompilesWithInlining() = execute(true, true)
    @Test fun widerPreparedCoreCompilesAcrossResidualCalls() = execute(false, true)
    @Test fun nativeFamiliesWithInlining() = execute(true, false)
    @Test fun nativeFamiliesAcrossResidualCalls() = execute(false, false)

    private fun execute(inlining: Boolean, earlyWideGate: Boolean) {
        val manifest = Json.parse(File(directory, "manifest.json").readText()) as Map<String, Any?>
        for (item in (manifest["inputs"] as List<Map<String, String>>) + (manifest["artifacts"] as List<Map<String, String>>)) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, item.getValue("path")).readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(item["sha256"], hash, "Stale SIMD source/artifact: ${item["path"]}")
        }
        val expectedText = File(directory, "expected.tsv").readText()
        if (!earlyWideGate) {
            assertEquals(expectedText.lines().count { it.isNotEmpty() }.toLong(), (manifest["nativeRows"] as Number).toLong())
            assertEquals(expectedText, File(directory, "oracle.tsv").readText())
            assertEquals(listOf("pre", "post"), manifest["stages"])
        }
        val rows = expectedText.lineSequence().filter(String::isNotEmpty).map { it.split('\t') }.groupBy { it[0] }
        val names = if (earlyWideGate) listOf("timesInt32X8", "timesInt32X16", "timesWord64X2",
            "timesWord32X8", "floatX8Composite", "doubleX4Composite") else rows.keys.toList()
        for (stage in manifest["stages"] as List<String>) {
            val module = Json.parse(File(directory, "$stage-core/GeneratedSimdFamilies.json").readText()) as Map<String, Any?>
            val calls = ((manifest["structures"] as Map<String, Map<String, Any?>>).getValue(stage)["expectedGuestCalls"] as Map<String, Number>)
            for (name in names) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
                context.initialize("thc"); context.enter()
                try {
                    val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                    val input = CoreModules.reachable(module, name) + ("instrument" to true)
                    val program: ExecutableProgram = if (backend == "ast") Program(language, input) else BytecodeProgram(language, input)
                    val entry = program.entryTarget(name); val host = program.hostEntryTarget(3)
                    val function = context.asValue(EntryValue(program, name, 3))
                    val cases = when {
                        !earlyWideGate -> rows.getValue(name)
                        name.endsWith("Composite") -> rows.getValue(name).groupBy { it[1] }.values.flatMap { selectorRows ->
                            // Each selector reaches a distinct operation and output lane. Use finite
                            // normal inputs in the early JVM gate; the native oracle retains every edge row.
                            val indices = if (selectorRows.size == 28) listOf(12, 14, 16) else listOf(348, 350, 404)
                            indices.map(selectorRows::get)
                        }
                        else -> rows.getValue(name).filterIndexed { i, _ -> i % 225 in listOf(0, 112, 224) }
                    }
                    val label = "$stage/$backend/$name/inline=$inlining"
                    fun check(row: List<String>) {
                        assertEquals(row[4].toLong(), function.execute(row[1].toLong(), row[2].toLong(), row[3].toLong()).asLong(), "$label/${row.drop(1)}")
                    }
                    cases.forEach(::check)
                    val active = activeTargets(host)
                    assertEquals(calls.getValue(name).toInt() + 1, active.size, "$label actual target count")
                    (active + entry).distinct().filter { it !== host }.forEach(::compile)
                    assertTrue(function.invokeMember("compile").asBoolean(), "$label host installation")
                    for (row in cases) {
                        val before = count(program)
                        check(row)
                        assertEquals(before + calls.getValue(name).toLong(), count(program), "$label exact guest entries")
                        assertEquals(active, activeTargets(host), "$label actual target identity")
                        valid(entry, "$label original"); active.forEach { valid(it, "$label active") }; released(language)
                    }
                    for (counter in listOf("unsupportedTraps", "blackholes")) {
                        assertEquals(0L, (program.diagnostics().getValue(counter) as Number).toLong(), "$label/$counter")
                    }
                } finally { context.leave() }
            }
        }
    }
}
