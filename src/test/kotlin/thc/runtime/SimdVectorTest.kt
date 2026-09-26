// SPDX-FileCopyrightText: 2026 Edward Kmett
// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause

@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import jdk.incubator.vector.LongVector

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File

class SimdVectorTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val inputs = longArrayOf(Long.MIN_VALUE, -3000000001L, -1, 0, 1, 9000000003L, Long.MAX_VALUE)
    private fun module(stage: String = "pre") = Json.parse(File(root, "build/simd/$stage-core/SimdInt64X2.json").readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").option("engine.SingleTierCompilationThreshold", "10000000").build()
    private fun withLanguage(action: (Language) -> Unit) = context().use { context ->
        context.initialize("thc"); context.enter()
        try { action(TruffleLanguage.LanguageReference.create(Language::class.java).get(null)) } finally { context.leave() }
    }
    private fun program(language: Language, backend: String, module: Map<String, Any?>, entry: String): ExecutableProgram {
        val linked = CoreModules.reachable(module, entry)
        return if (backend == "ast") Program(language, linked) else BytecodeProgram(language, linked)
    }
    @Test fun exactVectorMetadataAndDurableStorageAreDistinctFromTuples() {
        val metadata = mapOf("kind" to "vector", "primReps" to listOf("VecRep 2 Int64ElemRep"), "evaluated" to true,
            "vector" to mapOf("lanes" to 2L, "element" to "Int64ElemRep"))
        val proof = CoreRepresentations.parse(metadata)
        assertTrue(proof.isVector); assertFalse(proof.isTuple); assertFalse(proof.isLong)
        assertFalse(TupleShape.compatible(proof, CoreVectors.unpacked))
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata - "vector") }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata + ("kind" to "unknown")) }
        assertThrows(RuntimeFault::class.java) { CoreRepresentations.parse(metadata + ("primReps" to listOf("Int64Rep", "Int64Rep"))) }
        assertEquals(CoreVector(4, "Int64ElemRep"), CoreRepresentations.parse(metadata + mapOf(
            "primReps" to listOf("VecRep 4 Int64ElemRep"), "vector" to mapOf("lanes" to 4L, "element" to "Int64ElemRep"))).vector)
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(metadata + mapOf(
            "primReps" to listOf("VecRep 3 Int64ElemRep"), "vector" to mapOf("lanes" to 3L, "element" to "Int64ElemRep"))) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.proof.refine(CoreVectors.unpacked) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packInt64X2#", listOf(CoreVectors.proof), CoreVectors.proof) }
        assertEquals(CoreVectors.proof, CoreVectors.caseResult(listOf(CoreVectors.proof, CoreVectors.proof)))
        assertThrows(RuntimeFault::class.java) { CoreVectors.caseResult(listOf(CoreVectors.proof, CoreVectors.unpacked)) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.caseResult(listOf(CoreVectors.proof, CoreRepresentation.UNKNOWN)) }
        // Every supported family enters through the same parser, before lane-count
        // normalization. Genuine JSON integer counts keep their existing identity.
        for (expected in listOf(CoreVectors.proof, CoreVectors.proof32, CoreVectors.proof16,
            CoreVectors.proof8, CoreVectors.proofWord8, CoreVectors.proofWord16,
            CoreVectors.proofWord32, CoreVectors.proofFloat, CoreVectors.proofDouble)) {
            val vector = requireNotNull(expected.vector)
            fun record(count: Any?) = mapOf("kind" to "vector", "primReps" to expected.primReps,
                "evaluated" to true, "vector" to mapOf("lanes" to count, "element" to vector.element))
            for (count in listOf(vector.lanes, vector.lanes.toLong())) {
                assertEquals(expected, CoreRepresentations.parse(record(count)))
                assertEquals(expected, CoreRepresentations.parse(Json.parse(Json.stringify(record(count)))))
            }
            for (count in listOf(vector.lanes.toDouble(), vector.lanes + 0.5, true, vector.lanes.toString(), null))
                assertThrows(RuntimeFault::class.java, { CoreRepresentations.parse(record(count)) }, "$vector lanes=$count")
        }
    }
    @Test fun vectorCallsLoadButConflictingResultAndHiddenFormalProofsFail() = withLanguage { language ->
        for (backend in listOf("ast", "bytecode")) {
            assertNotNull(program(language, backend, module(), "branchCase"))
            val m = module().toMutableMap()
            val binding = (m["bindings"] as List<Map<String, Any?>>).single { it["name"] == "vectorCase" }
            val expression = (binding["expr"] as List<Any?>).toMutableList()
            val vector = mapOf("kind" to "vector", "primReps" to listOf("VecRep 2 Int64ElemRep"), "evaluated" to true,
                "vector" to mapOf("lanes" to 2L, "element" to "Int64ElemRep"))
            expression[3] = (expression[3] as Map<String, Any?>) + ("resultRep" to vector)
            m["bindings"] = listOf(binding + ("expr" to expression))
            val error = assertThrows(RuntimeFault::class.java) { program(language, backend, m, "vectorCase") }
            assertTrue(error.message.orEmpty().contains("Conflicting Core vector representation proofs"),
                "$backend: ${error.message}")
            // An occurrence proof cannot smuggle a vector through an untyped formal.
            val hidden = module().toMutableMap()
            val hBinding = (hidden["bindings"] as List<Map<String, Any?>>).single { it["name"] == "vectorCase" }
            val hExpression = hBinding["expr"] as MutableList<Any?>
            val hArguments = hExpression[1] as MutableList<Any?>
            val first = hArguments[0] as MutableMap<String, Any?>
            first.remove("rep")
            val outerCase = hExpression[2] as MutableList<Any?>
            val unpack = outerCase[1] as MutableList<Any?>
            unpack[2] = listOf(listOf("var", first["id"], mapOf("rep" to vector)))
            hidden["bindings"] = listOf(hBinding)
            assertThrows(RuntimeFault::class.java) { program(language, backend, hidden, "vectorCase") }
        }
    }
    @Test fun vectorJoinFormalLoadsWithoutAnEarlierVectorLet() = withLanguage { language ->
        val m = module().toMutableMap()
        val binding = (m["bindings"] as List<Map<String, Any?>>).single { it["name"] == "branchCase" }
        val expression = (binding["expr"] as List<Any?>).toMutableList()
        val outer = expression[2] as List<Any?>
        val vectorBinding = (outer[2] as List<Map<String, Any?>>).single()
        val vectorId = vectorBinding["id"]
        // Inline only the ordinary vector let; retain GHC's genuine join and its formal.
        fun inline(value: Any?): Any? = when (value) {
            is List<*> -> if (value.firstOrNull() == "var" && value.getOrNull(1) == vectorId)
                vectorBinding["expr"] else value.map(::inline)
            is Map<*, *> -> value.mapValues { inline(it.value) }
            else -> value
        }
        expression[2] = inline(outer[3])
        m["bindings"] = listOf(binding + ("expr" to expression))
        for (backend in listOf("ast", "bytecode")) {
            assertNotNull(program(language, backend, m, "branchCase"))
        }
    }
    @Test fun inferredVectorCaseResultRetainsItsShapeAndCannotBecomeAScalar() = withLanguage { language ->
        val m = module().toMutableMap()
        val binding = (m["bindings"] as List<Map<String, Any?>>).single { it["name"] == "vectorCase" }
        val expression = (binding["expr"] as List<Any?>).toMutableList()
        val unpack = (expression[2] as List<Any?>)[1] as List<Any?>
        val vector = (unpack[2] as List<List<Any?>>).single()
        val first = (expression[1] as List<Map<String, Any?>>).first()
        val scalar = listOf("var", first["id"], mapOf("rep" to first["rep"]))
        val hiddenCase = listOf("case", scalar,
            "vector-result-scrutinee", listOf(listOf("default", null, emptyList<String>(), vector)))
        expression[3] = (expression[3] as Map<String, Any?>) - "resultRep"
        val argument = listOf("app", listOf("prim", "+#"), listOf(hiddenCase, scalar), listOf(false, false), false, true,
            mapOf("rep" to first["rep"]))
        val join = listOf("let", false, listOf(mapOf("id" to "vector-join", "name" to "vector-join", "lifted" to false,
            "joinValueArity" to 0L, "expr" to hiddenCase)), listOf("var", "vector-join"))
        for (backend in listOf("ast", "bytecode")) {
            // The branch supplies an exact vector proof even without an outer
            // case/lambda result record. Guest returns now preserve that shape.
            expression[2] = hiddenCase
            m["bindings"] = listOf(binding + ("expr" to expression.toList()))
            val inferred = program(language, backend, m, "vectorCase")
            val resultProof = (inferred.entryTarget("vectorCase").rootNode as GuestRoot).tupleResult?.proof
            assertNotNull(resultProof, backend)
            assertTrue(TupleShape.compatible(CoreVectors.proof, resultProof!!), backend)
            for ((body, message) in listOf(
                argument to "Unsupported Core vector boundary: argument",
                join to "Conflicting logical tuple representation proofs")) {
                expression[2] = body
                m["bindings"] = listOf(binding + ("expr" to expression.toList()))
                val error = assertThrows(RuntimeFault::class.java) { program(language, backend, m, "vectorCase") }
                assertTrue(error.message.orEmpty().startsWith(message), "$backend: ${error.message}")
            }
        }
    }
    @Test fun exactVectorCaseStaysLocalAndExecutesCompiled() = withLanguage { language ->
        val m = module().toMutableMap()
        val binding = (m["bindings"] as List<Map<String, Any?>>).single { it["name"] == "vectorCase" }
        val expression = binding["expr"] as List<Any?>
        val unpack = (expression[2] as List<Any?>)[1] as MutableList<Any?>
        val vector = (unpack[2] as List<List<Any?>>).single()
        val first = (expression[1] as List<Map<String, Any?>>).first()
        unpack[2] = listOf(listOf("case", listOf("var", first["id"], mapOf("rep" to first["rep"])),
            "vector-local-case", listOf(listOf("default", null, emptyList<String>(), vector)),
            mapOf("rep" to (vector[6] as Map<String, Any?>)["rep"])))
        m["bindings"] = listOf(binding)
        for (backend in listOf("ast", "bytecode")) {
            val program = program(language, backend, m, "vectorCase")
            fun check() {
                for (a in listOf(Long.MIN_VALUE, 123L, Long.MAX_VALUE)) {
                    val b = -4097L
                    assertEquals(((a+a+91)*7) xor ((b+a+91)*11),
                        Calls.target(program.hostEntryTarget(2), arrayOf(program.entryValue("vectorCase"), arrayOf(a, b))))
                }
            }
            check(); check()
            val target = program.entryTarget("vectorCase")
            target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
            check()
            assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), backend)
        }
    }
    @Test fun realCoreVectorArithmeticRemainsInstalledAfterCompiledExecution() {
        val provenance = Json.parse(File(root, "build/simd/provenance.json").readText()) as Map<String, Any?>
        val stages = provenance["stages"] as List<String>
        assertTrue("pre" in stages, "Run scripts/prepare-simd-audit.py first")
        for (artifact in provenance["artifacts"] as List<Map<String, String>>) {
            val bytes = File(root, artifact.getValue("path")).readBytes()
            val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(artifact["sha256"], digest, "Stale SIMD artifact")
        }
        val oracle = File(root, "build/simd/oracle.tsv").takeIf { provenance["nativeRows"] != null }?.readLines()?.associate { row ->
            val v = row.split('\t'); Triple(v[0], v[1].toLong(), v[2].toLong()) to v[3].toLong()
        }
        for (stage in stages) for (backend in listOf("ast", "bytecode")) withLanguage { language ->
            for (entry in listOf("vectorCase", "subtractCase")) {
                val program = program(language, backend, module(stage), entry)
                fun checkRows(requireCompiledEntry: Boolean = false) {
                    for (a in inputs) for (b in inputs) {
                        val expected = if (entry == "vectorCase") ((a+a+91)*7) xor ((b+a+91)*11)
                            else ((a+b-19)*13) xor ((b+b-19)*17)
                        if (oracle != null) assertEquals(expected, oracle[Triple(entry, a, b)])
                        val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                        val result = Calls.target(program.hostEntryTarget(2), arrayOf(program.entryValue(entry), arrayOf(a, b)))
                        assertEquals(expected, result, "$stage/$backend/$entry/$a/$b")
                        if (requireCompiledEntry) assertEquals(before + 1,
                            (program.diagnostics().getValue("compiledEntries") as Number).toLong(),
                            "$stage/$backend/$entry/$a/$b must enter compiled code exactly once")
                    }
                }
                checkRows(); checkRows()
                val target = program.entryTarget(entry)
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                checkRows(requireCompiledEntry = true)
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$entry after execution")
                assertEquals(0, language.handoffState.get().results.depth)
            }
        }
    }
}
