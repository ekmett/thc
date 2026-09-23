package thc

import org.graalvm.polyglot.Value
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File
import java.security.MessageDigest

/** Ordinary optimized Haskell, compared with native GHC across both execution backends. */
class CoverageCorpusTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private fun diagnostics(function: Value): Map<*, *> =
        Json.parse(function.getMember("diagnostics").asString()) as Map<*, *>
    private fun count(function: Value, key: String): Long = (diagnostics(function)[key] as Number).toLong()

    @TestFactory fun nativeOracleBeforeCompilationOnColdPathsAndAfterRecompilation(): List<DynamicTest> {
        val corpus = Json.parse(File(root, "build/corpus/corpus.json").readText()) as Map<*, *>
        val manifest = File(root, "examples/coverage.json").readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(manifest).joinToString("") { "%02x".format(it) }
        assertEquals(digest, corpus["sourceManifestSha256"], "Run scripts/prepare-tests.sh after changing the corpus")
        for ((path, expectedHash) in (corpus["inputHashes"] as Map<*, *>) + (corpus["artifactHashes"] as Map<*, *>)) {
            val actualHash = MessageDigest.getInstance("SHA-256").digest(File(root, path as String).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertEquals(expectedHash, actualHash, "Stale native corpus input/artifact: $path; run scripts/prepare-tests.sh")
        }
        val entries = corpus["entries"] as List<*>
        assertTrue(entries.isNotEmpty(), "Coverage corpus must not be empty")
        return listOf("ast", "bytecode").flatMap { backend -> entries.map { raw ->
            val entry = raw as Map<*, *>
            val id = entry["id"] as String
            val modules = (entry["modules"] as List<*>).map { File(root, it as String).path }
            val warm = (entry["warmInputs"] as List<*>).map { (it as Number).toLong() }
            val cold = (entry["coldInputs"] as List<*>).map { (it as Number).toLong() }
            val expected = entry["expected"] as Map<*, *>
            val shared = (entry["sharedBindings"] as? List<*>)?.map { it as String } ?: emptyList()
            DynamicTest.dynamicTest("$backend $id: ${entry["focus"]}") {
                executionContext().use { context ->
                    // Strict loading is deliberate: unsupported branches cannot silently become traps.
                    val function = context.eval("thc", CoreModules.request(modules, entry["name"] as String,
                        instrument = true, diagnosticUnsupported = false, backend = backend))
                    assertEquals(backend, diagnostics(function)["backend"])
                    fun labelCount(label: String): Long =
                        ((diagnostics(function)["thunkEvaluationsByLabel"] as Map<*, *>)[label] as? Number)?.toLong() ?: 0L
                    fun check(n: Long) {
                        val evaluations = shared.associateWith(::labelCount)
                        assertEquals((expected[n.toString()]!! as Number).toLong(), function.execute(n).asLong(),
                            "$backend $id($n)")
                        for (label in shared) assertEquals(1L, labelCount(label) - evaluations.getValue(label),
                            "$backend $id($n): shared $label must be evaluated exactly once")
                    }
                    warm.forEach(::check)
                    repeat(40) { check(warm[it % warm.size]) }
                    assertTrue(function.invokeMember("compile").asBoolean(), "$backend $id compilation")
                    var before = count(function, "compiledEntries")
                    warm.forEach(::check)
                    assertTrue(count(function, "compiledEntries") > before, "$backend $id installed guest code")
                    // These inputs are withheld until after the first compilation; deoptimization is allowed.
                    cold.forEach(::check)
                    val all = warm + cold
                    repeat(40) { check(all[it % all.size]) }
                    assertTrue(function.invokeMember("compile").asBoolean(), "$backend $id recompilation")
                    for (n in all.reversed()) {
                        before = count(function, "compiledEntries")
                        check(n)
                        assertTrue(count(function, "compiledEntries") > before,
                            "$backend $id($n) must enter installed recompiled code")
                    }
                    assertEquals(0L, count(function, "unsupportedTraps"), "$backend $id unsupported paths")
                    assertEquals(0L, count(function, "blackholes"), "$backend $id unintended strictness")
                }
            }
        } }
    }
}
