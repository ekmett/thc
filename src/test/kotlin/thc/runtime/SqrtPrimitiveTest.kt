@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

class SqrtPrimitiveTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    @BeforeEach fun verifyEvidence() {
        val evidence = Json.parse(File(root, "build/sqrt/provenance.json").readText()) as Map<String, Any?>
        val sources = evidence["sources"] as List<Map<String, String>>
        val artifacts = evidence["artifacts"] as List<Map<String, String>>
        for (record in sources + artifacts) {
            val hash = MessageDigest.getInstance("SHA-256").digest(File(root, record.getValue("path")).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(record["sha256"], hash, "Stale sqrt evidence: ${record["path"]}")
        }
        val required = listOf("scripts/audit-core.py", "scripts/core-capabilities.json",
            "src/main/resources/thc/scalar-primop-signatures.json", "scripts/generate-scalar-signatures.py") +
            File(root, "scripts").listFiles()!!.filter { it.name.startsWith("core_") && it.extension == "py" }
                .map { it.relativeTo(root).path }
        assertTrue(sources.map { it.getValue("path") }.containsAll(required))
        for (stage in listOf("pre", "post")) assertEquals(true,
            (Json.parse(File(root, "build/sqrt/$stage-audit.json").readText()) as Map<*, *>)["accepted"])
    }
    private fun module(stage: String) = Json.parse(File(root, "build/sqrt/$stage-core/SqrtAudit.json").readText()) as Map<String, Any?>
    private fun context(inlining: Boolean) = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("compiler.Inlining", inlining.toString()).option("engine.BackgroundCompilation", "false")
        .option("engine.MultiTier", "false").option("engine.CompilationFailureAction", "Throw").build()
    private fun valid(target: RootCallTarget, label: String) = assertEquals(true,
        target.javaClass.getMethod("isValidLastTier").invoke(target), label)
    private fun compile(target: RootCallTarget) {
        target.javaClass.getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target, "initial compilation")
    }
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun rows() = File(root, "build/sqrt/oracle.tsv").readLines().map { it.split('\t') }.groupBy { it[0] }
    private fun count(program: ExecutableProgram) = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
    private fun call(program: ExecutableProgram, entry: String, row: List<String>, label: String) {
        val input: Any = when (entry) {
            "sqrtFloat" -> Float.fromBits(row[1].toLong().toInt())
            "sqrtDouble" -> Double.fromBits(java.lang.Long.parseUnsignedLong(row[1]))
            else -> row[1].toLong()
        }
        val result = Calls.target(program.hostEntryTarget(1), arrayOf(program.entryValue(entry), arrayOf(input)))
        when (entry) {
            "sqrtFloat" -> {
                assertTrue(result is Float, label)
                val expected = Float.fromBits(row[2].toLong().toInt())
                if (expected.isNaN()) assertTrue((result as Float).isNaN(), label)
                else assertEquals(expected.toRawBits(), (result as Float).toRawBits(), label)
            }
            "sqrtDouble" -> {
                assertTrue(result is Double, label)
                val expected = Double.fromBits(java.lang.Long.parseUnsignedLong(row[2]))
                if (expected.isNaN()) assertTrue((result as Double).isNaN(), label)
                else assertEquals(expected.toRawBits(), (result as Double).toRawBits(), label)
            }
            else -> assertEquals(row[2].toLong(), result, label)
        }
    }
    @Test fun publicSqrtMatchesNativeWithInlining() = native(true)
    @Test fun publicSqrtMatchesNativeAcrossResidualCalls() = native(false)
    private fun native(inlining: Boolean) {
        val rows = rows()
        assertEquals(setOf("sqrtFloat", "sqrtDouble", "floatCase", "doubleCase"), rows.keys)
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(inlining).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for ((entry, selected) in rows) {
                    val linked = CoreModules.reachable(module(stage), entry)
                    val program = program(language, linked, backend)
                    val bindings = linked["bindings"] as List<Map<String, Any?>>
                    val target = program.entryTarget(entry)
                    for (row in selected) call(program, entry, row, "$backend/$entry/interpreter/${row[1]}")
                    bindings.filter { (it["expr"] as List<*>)[0] == "lam" }.forEach { compile(program.entryTarget(it["id"] as String)) }
                    for (row in selected) {
                        val label = "$stage/$backend/$entry/${row[1]}/inlining=$inlining"
                        val before = count(program)
                        call(program, entry, row, label)
                        assertEquals(before + if (entry.endsWith("Case")) 2 else 1, count(program), label)
                        valid(target, label)
                    }
                    assertEquals(0L, (program.diagnostics().getValue("unsupportedTraps") as Number).toLong())
                    assertEquals(0, language.handoffState.get().arguments.depth)
                    assertEquals(0, language.handoffState.get().results.depth)
                }
            } finally { context.leave() }
        }
    }
    @Test fun coldNaNsNegativesSubnormalsAndZeroSignsDoNotInvalidateCompiledSqrt() {
        for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (entry in listOf("sqrtFloat", "sqrtDouble")) {
                    val selected = rows().getValue(entry)
                    val program = program(language, CoreModules.reachable(module("pre"), entry), backend)
                    val one = if (entry == "sqrtFloat") 0x3f800000L else 0x3ff0000000000000L
                    val warm = selected.single { it[1] == one.toString() }
                    repeat(20) { call(program, entry, warm, "warm") }
                    val target = program.entryTarget(entry); compile(target)
                    for (row in selected) {
                        val before = count(program)
                        call(program, entry, row, "$backend/$entry/cold/${row[1]}")
                        assertEquals(before + 1, count(program), "$backend/$entry/${row[1]}")
                        valid(target, "$backend/$entry/${row[1]}")
                    }
                }
            } finally { context.leave() }
        }
    }
    @Test fun sqrtRequiresItsExactOperandAndResultProofs() {
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context(true).use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (entry in listOf("sqrtFloat", "sqrtDouble")) for (variant in listOf("argument", "result", "arity")) {
                    val linked = CoreModules.reachable(module(stage), entry)
                    val lambda = ((linked["bindings"] as List<Map<String, Any?>>).single()["expr"] as List<Any?>)
                    val body = lambda[2] as List<Any?>
                    val other = if (entry == "sqrtFloat") "double" else "float"
                    val proof = mapOf("kind" to other, "primReps" to listOf(if (other == "float") "FloatRep" else "DoubleRep"), "evaluated" to true)
                    if (variant == "arity") {
                        (body[2] as MutableList<Any?>).clear()
                        (body[3] as MutableList<Any?>).clear()
                        (body[6] as MutableMap<String, Any?>)["callDemand"] = mapOf("arity" to 0, "strictArgs" to emptyList<Boolean>())
                    } else if (variant == "argument") {
                        ((lambda[1] as List<MutableMap<String, Any?>>)[0])["rep"] = proof
                        (((body[2] as List<List<Any?>>)[0])[2] as MutableMap<String, Any?>)["rep"] = proof
                    } else {
                        (lambda[3] as MutableMap<String, Any?>)["resultRep"] = proof
                        (body[6] as MutableMap<String, Any?>)["rep"] = proof
                    }
                    val failure = assertThrows(RuntimeFault::class.java) { program(language, linked, backend) }
                    val detail = if (variant == "arity") "arity" else "representation"
                    assertTrue(failure.message.orEmpty().contains("Primitive $detail mismatch: $entry#"), failure.message)
                }
            } finally { context.leave() }
        }
    }
}
