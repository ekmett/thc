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
        assertThrows(UnsupportedCore::class.java) { CoreRepresentations.parse(metadata + mapOf("primReps" to listOf("VecRep 4 Int64ElemRep"), "vector" to mapOf("lanes" to 4L, "element" to "Int64ElemRep"))) }
        assertEquals(listOf(Long::class.javaPrimitiveType, Long::class.javaPrimitiveType), Int64X2::class.java.declaredFields.map { it.type })
        assertThrows(RuntimeFault::class.java) { CoreVectors.proof.refine(CoreVectors.unpacked) }
        assertThrows(RuntimeFault::class.java) { CoreVectors.validate("packInt64X2#", listOf(CoreVectors.proof), CoreVectors.proof) }
    }
    @Test fun vectorCallAndResultBoundariesStayExplicitlyUnsupported() = withLanguage { language ->
        for (backend in listOf("ast", "bytecode")) {
            val error = assertThrows(UnsupportedCore::class.java) { program(language, backend, module(), "branchCase") }
            assertTrue(error.message.orEmpty().contains("vector"), "$backend: ${error.message}")
            val m = module().toMutableMap()
            val binding = (m["bindings"] as List<Map<String, Any?>>).single { it["name"] == "vectorCase" }
            val expression = (binding["expr"] as List<Any?>).toMutableList()
            val vector = mapOf("kind" to "vector", "primReps" to listOf("VecRep 2 Int64ElemRep"), "evaluated" to true,
                "vector" to mapOf("lanes" to 2L, "element" to "Int64ElemRep"))
            expression[3] = (expression[3] as Map<String, Any?>) + ("resultRep" to vector)
            m["bindings"] = listOf(binding + ("expr" to expression))
            assertThrows(UnsupportedCore::class.java) { program(language, backend, m, "vectorCase") }
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
                fun checkRows() {
                    for (a in inputs) for (b in inputs) {
                        val expected = if (entry == "vectorCase") ((a+a+91)*7) xor ((b+a+91)*11)
                            else ((a+b-19)*13) xor ((b+b-19)*17)
                        if (oracle != null) assertEquals(expected, oracle[Triple(entry, a, b)])
                        val result = Calls.target(program.hostEntryTarget(2), arrayOf(program.entryValue(entry), arrayOf(a, b)))
                        assertEquals(expected, result, "$stage/$backend/$entry/$a/$b")
                    }
                }
                checkRows(); checkRows()
                val target = program.entryTarget(entry)
                target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
                checkRows()
                assertEquals(true, target.javaClass.getMethod("isValidLastTier").invoke(target), "$stage/$backend/$entry after execution")
                assertEquals(0, language.handoffState.get().results.depth)
            }
        }
    }
}
