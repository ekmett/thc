// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class SimdInt32VectorTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val inputs = longArrayOf(Long.MIN_VALUE, -2147483649, -2147483648, -1, 0, 1, 2147483647, 2147483648, Long.MAX_VALUE)
    private fun module(stage: String = "pre") = Json.parse(File(root, "build/simd-int32x4/$stage-core/SimdInt32X4.json").readText()) as Map<String, Any?>
    private fun withLanguage(action: (Language) -> Unit) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build().use { context ->
            context.initialize("thc"); context.enter()
            try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
        }
    private fun program(language: Language, backend: String, module: Map<String, Any?>, entry: String): ExecutableProgram {
        val linked = CoreModules.reachable(module, entry)
        return if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    }
    @Test fun exactInt32VectorIdentityAndDenseDurableStorage() {
        val metadata = mapOf("kind" to "vector", "primReps" to listOf("VecRep 4 Int32ElemRep"), "evaluated" to true,
            "vector" to mapOf("lanes" to 4L, "element" to "Int32ElemRep"))
        val proof = CoreRepresentations.parse(metadata)
        assertEquals(CoreVectors.proof32, proof)
        assertFalse(proof.isTuple); assertFalse(proof.isLong)
        assertEquals(List(4) { Int::class.javaPrimitiveType }, Int32X4::class.java.declaredFields.map { it.type })
        assertTrue(Int32X4::class.java.declaredFields.all { java.lang.reflect.Modifier.isFinal(it.modifiers) })
        assertFalse(TupleShape.compatible(proof, CoreVectors.unpacked32))
        assertThrows(RuntimeFault::class.java) { proof.refine(CoreVectors.proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.caseResult(listOf(proof, CoreVectors.proof)) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("plusInt32X4#", listOf(proof, CoreVectors.proof), proof) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packInt32X4#", listOf(CoreVectors.unpacked), proof) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata - "vector") }
        // Int32X8 is supported; a three-lane vector still has no carrier.
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(metadata + mapOf("primReps" to listOf("VecRep 3 Int32ElemRep"),
            "vector" to mapOf("lanes" to 3L, "element" to "Int32ElemRep"))) }
    }
    @Test fun vectorFormalJoinLoadsWithExactLaneProof() = withLanguage { language ->
        val m = module().toMutableMap()
        val binding = (m["bindings"] as List<Map<String, Any?>>).single { it["name"] == "branchCase" }
        val expression = (binding["expr"] as List<Any?>).toMutableList()
        val outer = expression[2] as List<Any?>
        val vectorBinding = (outer[2] as List<Map<String, Any?>>).single()
        fun inline(value: Any?): Any? = when (value) {
            is List<*> -> if (value.firstOrNull() == "var" && value.getOrNull(1) == vectorBinding["id"])
                vectorBinding["expr"] else value.map(::inline)
            is Map<*, *> -> value.mapValues { inline(it.value) }
            else -> value
        }
        expression[2] = inline(outer[3]); m["bindings"] = listOf(binding + ("expr" to expression))
        for (backend in listOf("ast", "bytecode")) {
            assertNotNull(program(language, backend, module(), "branchCase"))
            assertNotNull(program(language, backend, m, "branchCase"))
        }
    }
    @Test fun realInt32CorePreservesSignedLanesAndEntersCompiledCodeForEveryRow() {
        val provenance = Json.parse(File(root, "build/simd-int32x4/provenance.json").readText()) as Map<String, Any?>
        val stages = provenance["stages"] as List<String>
        assertTrue("pre" in stages)
        for (artifact in provenance["artifacts"] as List<Map<String, String>>) {
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(File(root, artifact.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(artifact["sha256"], hash, "Stale Int32X4 artifact")
        }
        val oracle = File(root, "build/simd-int32x4/oracle.tsv").takeIf { provenance["nativeRows"] != null }?.readLines()?.associate { row ->
            val values = row.split('\t'); (values[0] to values.subList(1, 5).map(String::toLong)) to values[5].toLong()
        }
        for (stage in stages) for (backend in listOf("ast", "bytecode")) withLanguage { language ->
            for (entry in listOf("vectorCase", "subtractCase")) {
                val program = program(language, backend, module(stage), entry)
                fun checkRows(compiled: Boolean = false) {
                    for ((i, a) in inputs.withIndex()) for ((j, b) in inputs.withIndex()) {
                        val values = listOf(a, b, inputs[(i + 3*j) % 9], inputs[(3*i + j + 1) % 9])
                        val bias = if (entry == "vectorCase") a + 91 else b - 19
                        val weights = if (entry == "vectorCase") listOf(7, 11, 13, 17) else listOf(13, 17, 19, 23)
                        val expected = values.zip(weights).fold(0L) { acc, (value, weight) -> acc xor ((value + bias).toInt().toLong() * weight) }
                        if (oracle != null) assertEquals(expected, oracle[entry to values])
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val result = Calls.target(program.hostEntryTarget(4), arrayOf(program.entryValue(entry), values.toTypedArray()))
                        val label = "$stage/$backend/$entry/$values"
                        assertEquals(expected, result, label)
                        if (compiled) assertEquals(before + 1, (program.diagnostics().getValue("compiledEntries") as Number).toLong(), label)
                    }
                }
                checkRows(); checkRows()
                val target = program.entryTarget(entry)
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                checkRows(compiled = true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$entry after execution")
                assertEquals(0, language.handoffState.get().results.depth)
            }
        }
    }
}
