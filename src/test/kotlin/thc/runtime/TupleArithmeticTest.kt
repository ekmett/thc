@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.RootCallTarget
import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import thc.CoreModules
import thc.Json
import thc.Language
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

class TupleArithmeticTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val names = listOf("quotRemInt", "quotRemWord", "addIntC", "subIntC", "plusWord2", "timesWord2")
    private fun module(stage: String = "pre") = Json.parse(File(root,
        "build/tuple-arithmetic/$stage-core/TupleArithmeticAudit.json").readText()) as Map<String, Any?>
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.CompilationFailureAction", "Throw").build()
    private fun program(language: Language, module: Map<String, Any?>, backend: String): ExecutableProgram =
        if (backend == "ast") Program(language, module) else BytecodeProgram(language, module)
    private fun valid(target: RootCallTarget) = assertEquals(true,
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("isValidLastTier").invoke(target))
    private fun compile(target: RootCallTarget) {
        Class.forName("com.oracle.truffle.runtime.OptimizedCallTarget").getMethod("compile", Boolean::class.javaPrimitiveType).invoke(target, true)
        valid(target)
    }
    private fun checkHashes() {
        val manifest = Json.parse(File(root, "build/tuple-arithmetic/manifest.json").readText()) as Map<String, Any?>
        for (kind in listOf("inputHashes", "artifactHashes")) for ((path, expected) in manifest[kind] as Map<String, String>) {
            val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            assertEquals(expected, actual, "Stale tuple arithmetic fixture: $path")
        }
    }
    private data class Row(val name: String, val x: Long, val y: Long, val first: Long, val second: Long)
    private fun mathematical(row: Row): Pair<Long, Long> {
        val modulus = BigInteger.ONE.shiftLeft(64)
        val x = BigInteger.valueOf(row.x); val y = BigInteger.valueOf(row.y)
        val unsignedX = x.mod(modulus); val unsignedY = y.mod(modulus)
        return when (row.name) {
            "quotRemInt" -> (x / y).toLong() to (x % y).toLong()
            "quotRemWord" -> (unsignedX / unsignedY).toLong() to (unsignedX % unsignedY).toLong()
            "addIntC", "subIntC" -> {
                val result = if (row.name == "addIntC") x + y else x - y
                result.toLong() to if (result < BigInteger.valueOf(Long.MIN_VALUE) || result > BigInteger.valueOf(Long.MAX_VALUE)) 1L else 0L
            }
            else -> {
                val result = if (row.name == "plusWord2") unsignedX + unsignedY else unsignedX * unsignedY
                result.shiftRight(64).toLong() to result.toLong()
            }
        }
    }
    @Test fun nativeFieldsAndUnboundedModelAgreeInBothBackendsAndInstalledCode() {
        checkHashes()
        val rows = File(root, "build/tuple-arithmetic/oracle.tsv").readLines().map {
            val r = it.split('\t'); Row(r[0], r[1].toLong(), r[2].toLong(), r[3].toLong(), r[4].toLong())
        }
        assertEquals(names.toSet(), rows.map { it.name }.toSet())
        for (row in rows) assertEquals(mathematical(row), row.first to row.second, "Native $row")
        for (stage in listOf("pre", "post")) for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val module = module(stage)
                val reached = names.flatMap { CoreModules.reachable(module, it)["bindings"] as List<Map<String, Any?>> }
                    .map { it["id"] }.toSet()
                val bindings = (module["bindings"] as List<Map<String, Any?>>).filter { it["id"] in reached }
                val program = program(language, module + ("bindings" to bindings), backend)
                val host = program.hostEntryTarget(3)
                val entries = names.associateWith { program.entryValue(it) }
                fun check(row: Row) {
                    for (field in 0L..1L) assertEquals(if (field == 0L) row.first else row.second,
                        Calls.target(host, arrayOf(entries.getValue(row.name), arrayOf(row.x, row.y, field))), "$stage/$backend/$row/$field")
                }
                // Establish the final host dispatch before warming individual roots. Six
                // targets replace its three-entry direct cache with indirect calls; with
                // handoff enabled this changes empty arguments to the ordinary packet.
                // Each root must see that packet during warmup, before we compile it.
                names.forEach { name -> check(rows.first { it.name == name }) }
                rows.forEach(::check)
                bindings.forEach { compile(program.entryTarget(it["id"] as String)) }
                val before = (program.diagnostics().getValue("compiledEntries") as Number).toLong()
                rows.asReversed().forEach(::check)
                assertEquals(2L * rows.size, (program.diagnostics().getValue("compiledEntries") as Number).toLong() - before,
                    "$stage/$backend: every checked field must enter installed guest code")
                bindings.forEach { valid(program.entryTarget(it["id"] as String)) }
                assertEquals(0L, language.handoffState.get().results.allocations, "Saturated primitive expressions need no tuple carrier")
                assertEquals(0, language.handoffState.get().results.depth)
                for (key in listOf("unsupportedTraps", "papAllocations", "thunkEvaluations", "blackholes"))
                    assertEquals(0L, (program.diagnostics().getValue(key) as Number).toLong(), key)
            } finally { context.leave() }
        }
    }
    private fun applications(value: Any?): List<MutableList<Any?>> = when (value) {
        is List<*> -> (if (value.firstOrNull() == "app") listOf(value as MutableList<Any?>) else emptyList()) + value.flatMap(::applications)
        is Map<*, *> -> value.values.flatMap(::applications)
        else -> emptyList()
    }
    private fun wrap(proof: Any?) = mapOf("aggregate" to "unboxed-tuple", "kind" to "unknown", "evaluated" to true,
        "primReps" to (proof as Map<String, Any?>)["primReps"], "components" to listOf(proof))
    @Test fun exactPrimitiveShapesAndSaturationAreRequired() {
        val mutations = listOf<(MutableList<Any?>) -> Unit>(
            { app -> val rep = (app[6] as MutableMap<String, Any?>)["rep"] as MutableMap<String, Any?>
                val children = rep["components"] as MutableList<Any?>; children[0] = wrap(children[0]) },
            { app -> (app[6] as MutableMap<String, Any?>).remove("rep") },
            { app -> val rep = (app[6] as MutableMap<String, Any?>)["rep"] as MutableMap<String, Any?>
                rep.remove("aggregate"); rep.remove("components"); rep["kind"] = "long"; rep["primReps"] = listOf("IntRep") },
            { app -> val arg = (app[2] as List<List<Any?>>)[0]; val rep = CoreRepresentations.metadata(arg)!!["rep"] as MutableMap<String, Any?>
                rep["primReps"] = if (rep["primReps"] == listOf("IntRep")) listOf("WordRep") else listOf("IntRep") },
            { app -> val arg = (app[2] as List<List<Any?>>)[0]
                (CoreRepresentations.metadata(arg)!!["rep"] as MutableMap<String, Any?>)["kind"] = "unknown" },
            { app -> (app[3] as MutableList<Any?>)[0] = true },
            { app -> (app[2] as MutableList<Any?>).removeAt(1); (app[3] as MutableList<Any?>).removeAt(1); (app[6] as MutableMap<String, Any?>).remove("callDemand") },
            { app -> (app[2] as MutableList<Any?>).add((app[2] as List<*>)[0]); (app[3] as MutableList<Any?>).add(false); (app[6] as MutableMap<String, Any?>).remove("callDemand") }
        )
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in names) for ((index, mutate) in mutations.withIndex()) for (diagnostic in listOf(false, true)) {
                    val module = CoreModules.reachable(module(), name)
                    val app = applications(module).single { (it[1] as List<*>).take(2) == listOf("prim", name + "#") }
                    mutate(app)
                    assertThrows(RuntimeFault::class.java, { program(language, module + ("diagnosticUnsupported" to diagnostic), backend) }, "$backend/$name/mutation$index")
                }
                for (name in names) {
                    val module = CoreModules.reachable(module(), name)
                    val app = applications(module).single { (it[1] as List<*>).take(2) == listOf("prim", name + "#") }
                    val primitive = (app[1] as List<*>).toList(); app.clear(); app.addAll(primitive)
                    assertThrows(UnsupportedCore::class.java, { program(language, module, backend) }, "$backend/$name first-class")
                }
            } finally { context.leave() }
        }
    }
    @Test fun undefinedDivisionInputsFailWithoutPublishingResultsAndValidCallsRecover() {
        for (backend in listOf("ast", "bytecode")) context().use { context ->
            context.initialize("thc"); context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                for (name in listOf("quotRemInt", "quotRemWord")) {
                    val program = program(language, CoreModules.reachable(module(), name), backend)
                    val host = program.hostEntryTarget(3); val entry = program.entryValue(name)
                    val invalid = listOf(1L to 0L) + if (name == "quotRemInt") listOf(Long.MIN_VALUE to -1L) else emptyList()
                    for ((x, y) in invalid) assertThrows(RuntimeFault::class.java) {
                        Calls.target(host, arrayOf(entry, arrayOf(x, y, 0L)))
                    }
                    assertEquals(2L, Calls.target(host, arrayOf(entry, arrayOf(7L, 3L, 0L))))
                    assertEquals(1L, Calls.target(host, arrayOf(entry, arrayOf(7L, 3L, 1L))))
                    assertEquals(0L, language.handoffState.get().results.allocations)
                }
            } finally { context.leave() }
        }
    }
}
