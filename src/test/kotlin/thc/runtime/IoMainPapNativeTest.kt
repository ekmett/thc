// SPDX-License-Identifier: UPL-1.0 AND BSD-3-Clause
@file:Suppress("UNCHECKED_CAST")
package thc.runtime

import com.oracle.truffle.api.TruffleLanguage
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.PolyglotException
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import thc.Json
import thc.Language
import java.io.File
import java.security.MessageDigest

/** Run once with each value of -Dthc.handoffSlabs; no global property mutation. */
class IoMainPapNativeTest {
    private val root = File(System.getProperty("thc.projectRoot"))
    private val folder = File(root, "build/io-main-pap")
    private val prefix = "main:IoMainPapAudit."
    private fun read(file: File) = Json.parse(file.readText()) as MutableMap<String, Any?>
    private fun modules(stage: String) = listOf("IoMainPapAudit.json", "THC.InterfaceClosure.json")
        .map { read(File(folder, "$stage/core/$it")) }
    private fun bindings(modules: List<Map<String, Any?>>) = modules.flatMap {
        it["bindings"] as List<MutableMap<String, Any?>>
    }
    private fun worker(modules: List<Map<String, Any?>>) =
        bindings(modules).single { it["id"] == prefix + "worker" }["expr"] as MutableList<Any?>
    private fun pap(modules: List<Map<String, Any?>>): MutableList<Any?> {
        val globals = bindings(modules).associateBy { it["id"] }
        var expr = globals.getValue(prefix + "goodMain")["expr"] as MutableList<Any?>
        val seen = mutableSetOf<Any?>()
        while (expr[0] == "var") {
            assertTrue(seen.add(expr[1]), "Unexpected cyclic genuine alias")
            expr = globals.getValue(expr[1])["expr"] as MutableList<Any?>
        }
        assertEquals("app", expr[0])
        assertEquals(2, (expr[2] as List<*>).size, "The genuine fixture must retain its two-argument PAP")
        return expr
    }
    private fun request(modules: List<Map<String, Any?>>, name: String, backend: String) = Json.stringify(mapOf(
        "modules" to modules, "entry" to prefix + name, "backend" to backend, "instrument" to true, "ioMain" to true))
    private fun context() = Context.newBuilder("thc").allowExperimentalOptions(true)
        .option("engine.BackgroundCompilation", "false").option("engine.MultiTier", "false")
        .option("engine.SingleTierCompilationThreshold", "10000000")
        .option("engine.CompilationFailureAction", "Throw").build()

    @BeforeEach fun currentNativeEvidence() {
        val evidence = read(File(folder, "provenance.json"))
        assertEquals("9.14.1", evidence["ghc"])
        for (kind in listOf("sources", "artifacts")) {
            val records = evidence[kind] as List<Map<String, String>>
            assertTrue(records.isNotEmpty(), "Missing $kind fingerprints")
            for (record in records) {
                val path = record.getValue("path")
                val actual = MessageDigest.getInstance("SHA-256").digest(File(root, path).readBytes())
                    .joinToString("") { "%02x".format(it.toInt() and 255) }
                assertEquals(record.getValue("sha256"), actual, "Stale IO-main PAP evidence: $path")
            }
        }
        assertEquals(listOf("goodMain\tcompleted", "badMain\tthrows"), File(folder, "oracle.tsv").readLines())
    }

    @Test fun genuinePreAndPostTidyPapsPassTheStrictIoAudit() {
        assertEquals(true, read(File(folder, "provenance.json"))["accepted"])
        for (stage in listOf("pre", "post")) for (name in listOf("goodMain", "badMain")) {
            val report = read(File(folder, "$stage/$name-audit.json"))
            assertEquals(true, report["accepted"], "$stage/$name: ${report["issues"]}")
            assertEquals(emptyList<Any>(), report["missingGlobals"])
        }
    }

    private fun released(language: Language) {
        val state = language.handoffState.get()
        assertEquals(0, state.arguments.depth)
        assertEquals(0, state.arguments.retainedReferences())
        assertNull(state.pending)
        assertEquals(0, state.results.depth)
        assertEquals(0, state.results.retainedReferences())
    }

    @ParameterizedTest
    @CsvSource("pre, ast", "post, ast", "pre, bytecode", "post, bytecode")
    fun nativeActionsActuallyRunRatherThanMerelyLoad(stage: String, backend: String) {
        val modules = modules(stage)
        pap(modules)
        context().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val good = context.eval("thc", request(modules, "goodMain", backend))
                val bad = context.eval("thc", request(modules, "badMain", backend))
                for (action in listOf(good, bad)) {
                    assertFalse(action.canExecute(), "IO actions must only expose runIO")
                    assertTrue(action.canInvokeMember("runIO"))
                }
                // A cached PAP is reusable, but its action must execute each time.
                repeat(3) {
                    assertTrue(good.invokeMember("runIO").asBoolean())
                    released(language)
                    val failure = assertThrows(PolyglotException::class.java) { bad.invokeMember("runIO") }
                    assertTrue(failure.isGuestException)
                    assertFalse(failure.isHostException)
                    released(language)
                    assertTrue(good.invokeMember("runIO").asBoolean(), "Failure must not poison later IO")
                    released(language)
                }
                for (action in listOf(good, bad)) {
                    val metrics = Json.parse(action.getMember("diagnostics").asString()) as Map<String, Any?>
                    assertTrue((metrics["papAllocations"] as Number).toLong() > 0, "Genuine PAP must execute")
                    assertEquals(0L, metrics["blackholes"])
                    assertEquals(0L, metrics["unsupportedTraps"])
                }
            } finally { context.leave() }
        }
    }

    @ParameterizedTest
    @CsvSource("pre, ast", "post, ast", "pre, bytecode", "post, bytecode")
    fun forgedRemainingStateAndResultMetadataStayRejected(stage: String, backend: String) {
        for (mutation in listOf("state-type", "state-rep", "state-result", "unit-result", "under-applied", "saturated", "entry-type")) {
            val modules = modules(stage)
            val lam = worker(modules)
            val formals = lam[1] as List<MutableMap<String, Any?>>
            val result = (lam.last() as MutableMap<String, Any?>)["resultRep"] as MutableMap<String, Any?>
            val components = result["components"] as MutableList<Any?>
            when (mutation) {
                "state-type" -> formals.last()["type"] = "State# s"
                "state-rep" -> formals.last()["rep"] = formals.first()["rep"]
                "state-result" -> {
                    components[0] = formals.first()["rep"]
                    result["primReps"] = listOf("IntRep", "BoxedRep (Just Lifted)")
                }
                "unit-result" -> {
                    components[1] = formals.first()["rep"]
                    result["primReps"] = listOf("IntRep")
                }
                "entry-type" -> bindings(modules).single { it["id"] == prefix + "goodMain" }["type"] = "IO Int"
                else -> {
                    val app = pap(modules)
                    val arguments = app[2] as MutableList<Any?>
                    val lifted = app[3] as MutableList<Any?>
                    if (mutation == "under-applied") {
                        arguments.removeAt(1); lifted.removeAt(1)
                    } else {
                        arguments.add(arguments[0]); lifted.add(false)
                    }
                }
            }
            context().use { context ->
                val error = assertThrows(PolyglotException::class.java, {
                    context.eval("thc", request(modules, "goodMain", backend))
                }, "$stage/$backend/$mutation")
                assertTrue(error.message.orEmpty().let { "IO main" in it || "requires main :: IO ()" in it },
                    "$stage/$backend/$mutation: ${error.message}")
            }
        }
    }

    @ParameterizedTest
    @CsvSource("pre, ast", "post, ast", "pre, bytecode", "post, bytecode")
    fun returningAnotherBoxedConstructorDoesNotMasqueradeAsUnit(stage: String, backend: String) {
        val modules = modules(stage)
        var replaced = 0
        fun replace(value: Any?) {
            when (value) {
                is MutableList<*> -> {
                    val node = value as MutableList<Any?>
                    if (node.size >= 2 && node[0] == "con" && node[1] == "ghc-internal:GHC.Internal.Tuple.()") {
                        node[1] = prefix + "WrongEffect"
                        replaced++
                    } else node.forEach(::replace)
                }
                is Map<*, *> -> value.values.forEach(::replace)
            }
        }
        replace(worker(modules))
        assertEquals(1, replaced, "One real successful tuple result is the negative control")
        context().use { context ->
            context.initialize("thc")
            context.enter()
            try {
                val language = TruffleLanguage.LanguageReference.create(Language::class.java).get(null)
                val action = context.eval("thc", request(modules, "goodMain", backend))
                val failure = assertThrows(PolyglotException::class.java) { action.invokeMember("runIO") }
                assertTrue("IO main did not return boxed unit" in failure.message.orEmpty(), failure.message)
                released(language)
            } finally { context.leave() }
        }
    }
}
